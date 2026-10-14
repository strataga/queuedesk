package dev.queuedesk.worker;

import dev.queuedesk.domain.BusinessState;
import dev.queuedesk.domain.PaymentInstruction;
import dev.queuedesk.domain.RequestHash;
import dev.queuedesk.domain.StoredInstruction;
import dev.queuedesk.messaging.InstructionReceivedEvent;
import dev.queuedesk.ops.QueuedeskMetrics;
import dev.queuedesk.ops.QueuedeskTracer;
import dev.queuedesk.repository.InstructionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

// WORK-01: one event → one database transaction holding the dedup check, the
// business transitions with their audit rows, the processed_message key and the
// attempt telemetry. The JMS delivery commits only after this service returns,
// so a crash in between redelivers an event the dedup key absorbs. The boundary
// is an explicit TransactionTemplate: an ordinary @Transactional here would
// cover the database only and silently exclude JMS (LEARN-02).
@Service
public class ProcessingService {
    public static class UnsupportedEnvelope extends RuntimeException {
        public UnsupportedEnvelope(String reason) { super(reason); }
    }
    public record Result(String outcome, String state, String reason) {}
    private final InstructionRepository instructions;
    private final ProcessedMessageRepository processed;
    private final ProcessingAttemptRepository attempts;
    private final ClearingHousePolicy clearing;
    private final ReceiverGateway receiver;
    private final ReconciliationTaskRepository tasks;
    private final QueuedeskMetrics metrics;
    private final TransactionTemplate tx;
    // Test seam: the rollback proof faults the business transaction once.
    private final AtomicBoolean dbFaultArmed = new AtomicBoolean();
    // Test seam: the D-01 proof faults AFTER the gateway committed, proving
    // receiver truth survives worker rollback.
    private final AtomicBoolean dbFaultAfterGatewayArmed = new AtomicBoolean();
    public void armDbFaultAfterGatewayOnce() { dbFaultAfterGatewayArmed.set(true); }
    public ProcessingService(InstructionRepository instructions, ProcessedMessageRepository processed,
            ProcessingAttemptRepository attempts, ClearingHousePolicy clearing,
            ReceiverGateway receiver, ReconciliationTaskRepository tasks, QueuedeskMetrics metrics,
            PlatformTransactionManager txManager) {
        this.instructions = instructions;
        this.processed = processed;
        this.attempts = attempts;
        this.clearing = clearing;
        this.receiver = receiver;
        this.tasks = tasks;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(txManager);
    }
    public void armDbFaultOnce() { dbFaultArmed.set(true); }
    public Result process(InstructionReceivedEvent envelope, int deliveryCount) {
        return tx.execute(status -> processInTransaction(envelope, deliveryCount));
    }
    // Controlled write AFTER a rollback: the failed transaction's rows vanished,
    // so this evidence commits in its own new transaction (DATA-02). It never
    // acknowledges business work; the caller still rolls the JMS delivery back.
    public void recordFailureAfterRollback(UUID instructionId, String eventId, String reason, int deliveryCount) {
        new TransactionTemplate(tx.getTransactionManager()).execute(s -> {
            attempts.insert(instructionId, eventId, "FAILED", reason, deliveryCount);
            return null;
        });
        metrics.failure("FAILED");
    }
    public void recordUnsupportedAfterRollback(UUID instructionId, String eventId, String reason, int deliveryCount) {
        new TransactionTemplate(tx.getTransactionManager()).execute(s -> {
            attempts.insert(instructionId, eventId, "UNSUPPORTED", reason, deliveryCount);
            return null;
        });
        metrics.failure("UNSUPPORTED");
    }
    // Routing markers: ROUTED commits before the session commit performs the
    // atomic move (a crash between the two re-routes honestly on redelivery);
    // ROUTE_FAILED is the terminal backstop when the move itself is impossible.
    public void recordRouted(UUID instructionId, String eventId, String reason, int deliveryCount) {
        new TransactionTemplate(tx.getTransactionManager()).execute(s -> {
            attempts.insert(instructionId, eventId, "ROUTED", reason, deliveryCount);
            return null;
        });
        metrics.failure("ROUTED");
    }
    public void recordRouteFailed(UUID instructionId, String eventId, String reason, int deliveryCount) {
        new TransactionTemplate(tx.getTransactionManager()).execute(s -> {
            attempts.insert(instructionId, eventId, "ROUTE_FAILED", reason, deliveryCount);
            return null;
        });
        metrics.failure("ROUTE_FAILED");
    }
    private Result processInTransaction(InstructionReceivedEvent envelope, int deliveryCount) {
        if (dbFaultArmed.compareAndSet(true, false)) throw new IllegalStateException("injected db fault");
        PaymentInstruction payload = validatedPayload(envelope);
        UUID instructionId = UUID.fromString(envelope.instructionId());
        var instruction = instructions.byId(instructionId)
                .orElseThrow(() -> new UnsupportedEnvelope("unknown instructionId " + envelope.instructionId()));
        if (!instruction.payload().runId().equals(envelope.runId()))
            throw new UnsupportedEnvelope("envelope runId does not match stored instruction");
        var handled = processed.find(envelope.eventId());
        if (handled.isPresent()) {
            // Redelivery of handled work: visible dedup evidence, zero business effect.
            attempts.insert(instructionId, envelope.eventId(), "DUPLICATE_SKIPPED", handled.get().outcomeReference(), deliveryCount);
            metrics.dedup();
            return new Result("DUPLICATE_SKIPPED", instruction.state(), instruction.reason());
        }
        Optional<String> clearingReject = clearing.review(payload);
        if (clearingReject.isPresent()) {
            move(instructionId, BusinessState.RECEIVED, BusinessState.REJECTED, clearingReject.get());
            processed.insert(envelope.eventId(), instructionId, "REJECTED:" + clearingReject.get());
            attempts.insert(instructionId, envelope.eventId(), "BUSINESS_REJECT", clearingReject.get(), deliveryCount);
            metrics.outcome(BusinessState.REJECTED, instruction.receivedAt());
            return new Result("BUSINESS_REJECT", BusinessState.REJECTED, clearingReject.get());
        }
        move(instructionId, BusinessState.RECEIVED, BusinessState.BUSINESS_ACCEPTED, null);
        // The gateway commits in its own transaction first (D-01): its truth
        // survives whatever happens below, and repeats return the same result.
        final ReceiverGateway.CallResult call;
        try {
            call = receiver.receive(instructionId, payload);
        } catch (ReceiverGateway.ResponseLost lost) {
            return deferPending(instructionId, envelope, deliveryCount, "RECEIVER_RESPONSE_LOST", lost.getMessage());
        } catch (ReceiverGateway.UncertainCall uncertain) {
            return deferPending(instructionId, envelope, deliveryCount, "RECEIVER_CALL_UNCERTAIN", uncertain.getMessage());
        }
        if (dbFaultAfterGatewayArmed.compareAndSet(true, false))
            throw new IllegalStateException("injected db fault after gateway commit");
        if (call.outcome().equals("REJECTED")) {
            move(instructionId, BusinessState.BUSINESS_ACCEPTED, BusinessState.REJECTED, call.reason());
            processed.insert(envelope.eventId(), instructionId, "REJECTED:" + call.reason());
            attempts.insert(instructionId, envelope.eventId(), "BUSINESS_REJECT", call.reason(), deliveryCount);
            metrics.outcome(BusinessState.REJECTED, instruction.receivedAt());
            return new Result("BUSINESS_REJECT", BusinessState.REJECTED, call.reason());
        }
        move(instructionId, BusinessState.BUSINESS_ACCEPTED, BusinessState.COMPLETED_SIMULATED, null);
        processed.insert(envelope.eventId(), instructionId, "COMPLETED_SIMULATED");
        attempts.insert(instructionId, envelope.eventId(), "SUCCESS", null, deliveryCount);
        metrics.outcome(BusinessState.COMPLETED_SIMULATED, instruction.receivedAt());
        return new Result("SUCCESS", BusinessState.COMPLETED_SIMULATED, null);
    }
    // Lost/uncertain reply: PENDING_CONFIRMATION, dedup key and reconciliation
    // task commit ATOMICALLY (D-03), so MQ redelivery absorbs as a duplicate
    // instead of repeating a business effect. Resolution comes only from the
    // poller's definitive lookup — never a blind reissue.
    private Result deferPending(UUID instructionId, InstructionReceivedEvent envelope, int deliveryCount,
            String reason, String detail) {
        move(instructionId, BusinessState.BUSINESS_ACCEPTED, BusinessState.PENDING_CONFIRMATION, reason);
        processed.insert(envelope.eventId(), instructionId, "PENDING_CONFIRMATION:" + reason);
        tasks.create(instructionId, envelope.eventId(), QueuedeskTracer.currentTraceparent(), null);
        attempts.insert(instructionId, envelope.eventId(), "PENDING", detail, deliveryCount);
        return new Result("PENDING", BusinessState.PENDING_CONFIRMATION, reason);
    }
    private void move(UUID instructionId, String from, String to, String reason) {
        if (!BusinessState.allowed(from, to))
            throw new UnsupportedEnvelope("illegal transition " + from + " -> " + to);
        // Not in the expected state with no dedup row is an impossible invariant
        // (same-tx design): poison, not a transient — it must quarantine, never
        // burn retries waiting for a state that cannot arrive.
        if (!instructions.transition(instructionId, from, to, reason, "worker"))
            throw new UnsupportedEnvelope("instruction " + instructionId + " is not " + from);
    }
    private static PaymentInstruction validatedPayload(InstructionReceivedEvent envelope) {
        if (envelope.schemaVersion() != InstructionReceivedEvent.SCHEMA_VERSION)
            throw new UnsupportedEnvelope("unsupported schemaVersion " + envelope.schemaVersion());
        if (!InstructionReceivedEvent.TYPE.equals(envelope.eventType()))
            throw new UnsupportedEnvelope("unsupported eventType " + envelope.eventType());
        if (envelope.payload() == null) throw new UnsupportedEnvelope("missing payload");
        if (envelope.eventId() == null || envelope.instructionId() == null)
            throw new UnsupportedEnvelope("missing eventId/instructionId");
        try {
            UUID.fromString(envelope.instructionId());
        } catch (IllegalArgumentException bad) {
            throw new UnsupportedEnvelope("unparseable instructionId");
        }
        var p = envelope.payload();
        final PaymentInstruction payload;
        try {
            payload = new PaymentInstruction(p.clientReference(), envelope.runId(),
                    p.senderParticipant(), p.receiverParticipant(), p.amountMinor(), p.currency());
        } catch (IllegalArgumentException bad) {
            throw new UnsupportedEnvelope("invalid payload: " + bad.getMessage());
        }
        if (!RequestHash.of(payload).equals(p.requestHash()))
            throw new UnsupportedEnvelope("requestHash mismatch: envelope was not built from this payload");
        if (envelope.correlationId() == null) throw new UnsupportedEnvelope("missing correlationId");
        return payload;
    }
}
