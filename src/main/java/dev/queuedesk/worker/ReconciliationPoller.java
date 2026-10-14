package dev.queuedesk.worker;

import dev.queuedesk.domain.BusinessState;
import dev.queuedesk.messaging.OutboxPublisher;
import dev.queuedesk.ops.LogContext;
import dev.queuedesk.ops.QueuedeskMetrics;
import dev.queuedesk.ops.QueuedeskTracer;
import dev.queuedesk.repository.InstructionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;

/**
 * Bounded reconciliation poller: settles payments whose receiver answer was
 * lost or uncertain, from stored receiver evidence.
 *
 * <p>Each pass claims a small batch of due tasks and settles each in its
 * own transaction. The order is strict: look up stored receiver truth
 * first; only when no definitive result exists may the gateway reissue.
 * Definitive evidence resolves the <em>original</em> instruction. This
 * poller never creates a replacement instruction, and a receiver that
 * still does not know leaves the payment visibly pending with its attempt
 * count and next due time on record.
 */
@Component
public class ReconciliationPoller {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationPoller.class);
    private final ReconciliationTaskRepository tasks;
    private final ReceiverResultRepository results;
    private final ReceiverGateway gateway;
    private final InstructionRepository instructions;
    private final ProcessingAttemptRepository attempts;
    private final QueuedeskMetrics metrics;
    private final QueuedeskTracer tracing;
    private final TransactionTemplate tx;
    private final boolean enabled;
    public ReconciliationPoller(ReconciliationTaskRepository tasks, ReceiverResultRepository results,
            ReceiverGateway gateway, InstructionRepository instructions,
            ProcessingAttemptRepository attempts, QueuedeskMetrics metrics, QueuedeskTracer tracing,
            PlatformTransactionManager txManager,
            @Value("${queuedesk.reconcile.enabled:true}") boolean enabled) {
        this.tasks = tasks;
        this.results = results;
        this.gateway = gateway;
        this.instructions = instructions;
        this.attempts = attempts;
        this.metrics = metrics;
        this.tracing = tracing;
        this.tx = new TransactionTemplate(txManager);
        this.enabled = enabled;
    }
    /** Scheduled entry point: one bounded pass while enabled. */
    @Scheduled(fixedDelayString = "${queuedesk.reconcile.poll-delay-ms:10000}")
    public void poll() { if (enabled) reconcilePass(); }

    /**
     * Claims up to 10 due tasks and tries to settle each one. A task that
     * fails or stays unknown is rescheduled with backoff; one bad task never
     * blocks the rest of the batch.
     *
     * @return how many tasks resolved to a terminal state in this pass
     */
    public int reconcilePass() {
        var due = tx.execute(s -> tasks.claimDue(10));
        int resolved = 0;
        for (var task : due) {
            // The settle joins the instruction's original trace via the
            // context stored on the task at defer time — no blind new trace.
            try (var span = tracing.child("queuedesk.reconcile.settle",
                    io.opentelemetry.api.trace.SpanKind.INTERNAL, task.traceparent(), task.tracestate(),
                    java.util.Map.of("business.instruction.id", task.instructionId().toString()))) {
                try {
                    if (tx.execute(s -> settle(task))) {
                        resolved++;
                        span.event("reconcile.resolved");
                    }
                } catch (RuntimeException failed) {
                    tx.execute(s -> {
                        tasks.findOpen(task.instructionId()).ifPresent(open ->
                                tasks.reschedule(open.taskId(), open.attempts() + 1,
                                        Instant.now().plusMillis(OutboxPublisher.backoffMs(open.attempts() + 1)),
                                        failed.toString()));
                        return null;
                    });
                    log.warn("reconcile.task.failed taskId={} error={}", task.taskId(), failed.toString());
                }
            }
        }
        if (!due.isEmpty()) log.info("reconcile.pass due={} resolved={}", due.size(), resolved);
        return resolved;
    }
    // One task, one transaction: lookup first, reissue only without definitive
    // evidence, resolve only the original instruction on definitive evidence.
    private boolean settle(ReconciliationTaskRepository.Task task) {
        var instruction = instructions.byId(task.instructionId());
        if (instruction.isEmpty()) {
            tasks.complete(task.taskId(), "instruction gone; nothing to resolve");
            return true;
        }
        var i = instruction.get();
        try (var scope = LogContext.of("stage", "reconcile", "instructionId", i.instructionId().toString(),
                "runId", i.payload().runId(), "correlationId", i.correlationId(), "eventId", task.eventId())) {
            return settleKnown(task, i);
        }
    }
    private boolean settleKnown(ReconciliationTaskRepository.Task task, dev.queuedesk.domain.StoredInstruction i) {
        var row = results.find(task.instructionId());
        if (row.isPresent() && row.get().outcome().equals("COMPLETED"))
            return resolve(task, i, BusinessState.COMPLETED_SIMULATED, null);
        if (row.isPresent() && row.get().outcome().equals("REJECTED"))
            return resolve(task, i, BusinessState.REJECTED, row.get().reason());
        if (row.isEmpty() || row.get().outcome().equals("PENDING")) {
            // Lookup found nothing definitive: a reissue is now safe (D-05).
            var reissued = gateway.reissue(task.instructionId());
            if (reissued.isPresent()) {
                var call = reissued.get();
                String target = call.outcome().equals("REJECTED") ? BusinessState.REJECTED : BusinessState.COMPLETED_SIMULATED;
                return resolve(task, i, target, call.reason());
            }
        }
        // Still unknown (or reissue inconclusive): remain pending, visibly.
        tasks.reschedule(task.taskId(), task.attempts() + 1,
                Instant.now().plusMillis(OutboxPublisher.backoffMs(task.attempts() + 1)), null);
        return false;
    }
    private boolean resolve(ReconciliationTaskRepository.Task task, dev.queuedesk.domain.StoredInstruction i,
            String to, String reason) {
        String from = i.state();
        if (BusinessState.isTerminal(from)) {
            tasks.complete(task.taskId(), "already " + from + "; converged");
            return true;
        }
        if (!BusinessState.allowed(from, to) || !instructions.transition(task.instructionId(), from, to, reason, "reconciler")) {
            tasks.reschedule(task.taskId(), task.attempts() + 1,
                    Instant.now().plusMillis(OutboxPublisher.backoffMs(task.attempts() + 1)),
                    "unexpected state " + from + " for " + to);
            return false;
        }
        attempts.insert(task.instructionId(), task.eventId(), "RECONCILED",
                "receiver evidence resolved " + to, null);
        tasks.complete(task.taskId(), null);
        metrics.reconciled();
        metrics.outcome(to, i.receivedAt());
        LogContext.set("outcome", to);
        log.info("reconcile.resolved taskId={} instructionId={} state={}", task.taskId(), task.instructionId(), to);
        return true;
    }
}
