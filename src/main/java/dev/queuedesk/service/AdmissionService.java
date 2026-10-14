package dev.queuedesk.service;

import tools.jackson.databind.ObjectMapper;
import dev.queuedesk.domain.*;
import dev.queuedesk.messaging.InstructionReceivedEvent;
import dev.queuedesk.messaging.OutboxRepository;
import dev.queuedesk.ops.QueuedeskTracer;
import dev.queuedesk.repository.InstructionRepository;
import dev.queuedesk.repository.PageCursor;
import dev.queuedesk.risk.RiskAssessmentRepository;
import dev.queuedesk.worker.ProcessingAttemptRepository;
import dev.queuedesk.worker.QuarantineRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

// Use case boundary: validation, idempotent admission, reads. Each database
// interaction runs in its own short transaction. A lost insert race retries the
// READ in a fresh transaction: Postgres aborts the failed one, so the same
// transaction can never be reused after a constraint violation.
@Service
public class AdmissionService {
    private static final Set<String> KNOWN_STATES = Set.of("RECEIVED", "BUSINESS_ACCEPTED", "REJECTED",
            "COMPLETED_SIMULATED", "PENDING_CONFIRMATION", "RISK_HELD");
    private static final int MAX_ADMISSIONS = 1000;
    private final InstructionRepository repository;
    private final OutboxRepository outbox;
    private final QuarantineRepository quarantine;
    private final ProcessingAttemptRepository attempts;
    private final RiskAssessmentRepository risk;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    // Constructor injection: Spring passes the repositories, mapper and transaction manager.
    public AdmissionService(InstructionRepository repository, OutboxRepository outbox,
            QuarantineRepository quarantine, ProcessingAttemptRepository attempts,
            RiskAssessmentRepository risk,
            PlatformTransactionManager txManager, ObjectMapper mapper) {
        this.repository = repository;
        this.outbox = outbox;
        this.quarantine = quarantine;
        this.attempts = attempts;
        this.risk = risk;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
        // Reads join instruction + outbox + quarantine + attempt evidence: one
        // REPEATABLE READ snapshot so the delivery derivation cannot tear
        // across concurrent worker commits. Plain READ COMMITTED would take a
        // fresh snapshot per statement and defeat the wrapping.
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTx.setReadOnly(true);
    }
    public record Admission(StoredInstruction instruction, boolean repeated) {}
    // PR08 read: business state plus separately derived delivery detail.
    // QUARANTINED beats RETRYING beats PUBLISHED beats PENDING_PUBLISH; delivery
    // may lag the worker and never implies business completion.
    // DASH-06: the latest risk assessment joins the read (null with the gate
    // OFF or before any assessment exists).
    public record Read(StoredInstruction instruction, String delivery, RiskAssessmentRepository.StoredAssessment risk) {}
    public record Page(List<Read> items, String nextCursor) {}
    public static class Conflict extends RuntimeException {}
    public static class Capacity extends RuntimeException {}
    public static class Missing extends RuntimeException {}

    public Admission admit(String key, String correlation, PaymentInstruction payload) {
        if (payload == null) throw new IllegalArgumentException("payload is required");
        key = PaymentInstruction.token(key, "Idempotency-Key");
        String finalKey = key;
        correlation = correlation == null ? UUID.randomUUID().toString()
                : PaymentInstruction.token(correlation, "X-Correlation-Id");
        String finalCorrelation = correlation;
        var previous = tx.execute(s -> repository.byKey(finalKey));
        if (previous != null && previous.isPresent()) {
            if (!previous.get().payload().equals(payload)) throw new Conflict();
            return new Admission(previous.get(), true);
        }
        try {
            return tx.execute(s -> {
                // Serialize the check-and-insert: COUNT(*) takes no lock that
                // blocks concurrent inserts, so without the advisory lock two
                // racers can both see 999 and overshoot the lab cap.
                repository.lockForAdmission();
                if (repository.count() >= MAX_ADMISSIONS) throw new Capacity();
                var now = Instant.now();
                var instruction = new StoredInstruction(UUID.randomUUID(), finalKey, finalCorrelation,
                        payload, now, now, "RECEIVED", null, 0);
                repository.save(instruction);
                // Durable publish intent joins the SAME transaction: instruction and event
                // commit together, and the repeat path above never creates a second event.
                String eventId = UUID.randomUUID().toString();
                // The admission span's context commits WITH the row: thread-local
                // context cannot survive dispatch or restart (TEL-01).
                outbox.insert(eventId, instruction.instructionId(), InstructionReceivedEvent.TYPE,
                        InstructionReceivedEvent.of(eventId, instruction, Instant.now()).toJson(mapper),
                        QueuedeskTracer.currentTraceparent(), null);
                return new Admission(instruction, false);
            });
        } catch (DuplicateKeyException raced) {
            // Lost an insert race: the loser's transaction rolled back, so re-read cleanly.
            var winner = tx.execute(s -> repository.byKey(finalKey).orElseThrow(() -> raced));
            if (!winner.payload().equals(payload)) throw new Conflict();
            return new Admission(winner, true);
        }
    }
    public Read get(UUID id) {
        return readTx.execute(s -> {
            var instruction = repository.byId(id).orElseThrow(Missing::new);
            return new Read(instruction, deliveryFor(instruction),
                    risk.latestByInstruction(id).orElse(null));
        });
    }
    public Page list(String runId, String status, int limit, String cursor, Instant since, Instant until,
            boolean newestFirst) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        if (status != null && !KNOWN_STATES.contains(status))
            throw new IllegalArgumentException("unknown status filter: " + status);
        if (since != null && until != null && since.isAfter(until))
            throw new IllegalArgumentException("since must not be after until");
        if (runId != null) PaymentInstruction.token(runId, "runId");
        var decoded = PageCursor.decode(cursor);
        return readTx.execute(s -> {
            var rows = repository.list(runId, status, limit, decoded, since, until, newestFirst);
            boolean more = rows.size() > limit;
            var pageRows = more ? rows.subList(0, limit) : rows;
            var items = assemble(pageRows);
            if (!more) return new Page(items, null);
            var last = pageRows.get(pageRows.size() - 1);
            return new Page(items, PageCursor.encode(last.receivedAt(), last.instructionId()));
        });
    }
    // Newest-first lab-wide recents for the overview table: same batched joins
    // as list, first page only (no cursor — the table shows the latest few).
    public List<Read> recent(int limit) {
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit must be 1..20");
        return readTx.execute(s -> assemble(repository.recent(limit)));
    }
    private List<Read> assemble(List<StoredInstruction> rows) {
        var ids = rows.stream().map(StoredInstruction::instructionId).toList();
        var published = outbox.publishedByInstruction(ids);
        var held = quarantine.quarantinedIds(ids);
        var failing = attempts.withFailures(ids);
        var assessed = risk.latestByInstructions(ids);
        return rows.stream()
                .map(i -> new Read(i, deriveDelivery(i, published, held, failing), assessed.get(i.instructionId())))
                .toList();
    }
    private String deliveryFor(StoredInstruction instruction) {
        var ids = List.of(instruction.instructionId());
        return deriveDelivery(instruction, outbox.publishedByInstruction(ids),
                quarantine.quarantinedIds(ids), attempts.withFailures(ids));
    }
    private static String deriveDelivery(StoredInstruction i, Set<UUID> published, Set<UUID> held, Set<UUID> failing) {
        if (held.contains(i.instructionId())) return "QUARANTINED";
        if (failing.contains(i.instructionId()) && !BusinessState.isTerminal(i.state())) return "RETRYING";
        return published.contains(i.instructionId()) ? "PUBLISHED" : "PENDING_PUBLISH";
    }
}
