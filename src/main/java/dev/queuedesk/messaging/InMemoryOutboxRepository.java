package dev.queuedesk.messaging;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

// Plain unit-test double for the outbox: no Spring stereotype, constructed directly.
public class InMemoryOutboxRepository implements OutboxRepository {
    private record Row(long outboxId, String eventId, UUID instructionId, String payload,
            int attempts, boolean published, String traceparent, String tracestate, java.time.Instant nextDue) {}
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    @Override
    public synchronized void insert(String eventId, UUID instructionId, String eventType, String payload,
            String traceparent, String tracestate) {
        rows.put(eventId, new Row(ids.incrementAndGet(), eventId, instructionId, payload, 0, false, traceparent, tracestate,
                java.time.Instant.EPOCH));
    }
    @Override
    public synchronized List<DueRow> dueBatch(int limit) {
        // Mirror the JDBC adapter: due rows only, strict id-order prefix —
        // followers wait behind a deferred head.
        var now = java.time.Instant.now();
        return rows.values().stream().filter(r -> !r.published())
                .sorted(Comparator.comparingLong(Row::outboxId))
                .takeWhile(r -> !r.nextDue().isAfter(now)).limit(limit)
                .map(r -> new DueRow(r.outboxId(), r.eventId(), r.instructionId(), r.payload(), r.attempts(),
                        r.traceparent(), r.tracestate()))
                .toList();
    }
    @Override
    public synchronized void markPublished(long outboxId) {
        rows.replaceAll((k, r) -> r.outboxId() == outboxId
                ? new Row(r.outboxId(), r.eventId(), r.instructionId(), r.payload(), r.attempts(), true,
                        r.traceparent(), r.tracestate(), r.nextDue()) : r);
    }
    @Override
    public synchronized void deferOnFailure(long outboxId, int attempts, java.time.Instant nextDue, String lastError) {
        rows.replaceAll((k, r) -> r.outboxId() == outboxId
                ? new Row(r.outboxId(), r.eventId(), r.instructionId(), r.payload(), attempts, false,
                        r.traceparent(), r.tracestate(), nextDue) : r);
    }
    @Override
    public synchronized java.util.Set<java.util.UUID> publishedByInstruction(java.util.Collection<java.util.UUID> instructionIds) {
        var wanted = new java.util.HashSet<>(instructionIds);
        var done = new java.util.HashSet<java.util.UUID>();
        for (var r : rows.values()) if (r.published() && wanted.contains(r.instructionId())) done.add(r.instructionId());
        return done;
    }
    @Override
    public synchronized long countPending() { return rows.values().stream().filter(r -> !r.published()).count(); }
    @Override
    public long oldestPendingAgeMs() { return 0; }
    @Override
    public synchronized long[] countByRun(String runId) {
        // The in-memory double tracks no run evidence; only the JDBC adapter serves it.
        return new long[2];
    }
    @Override
    public synchronized long[] countLab() {
        long pending = rows.values().stream().filter(r -> !r.published()).count();
        return new long[]{pending, rows.size() - pending};
    }
}
