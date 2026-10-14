package dev.queuedesk.messaging;

import java.util.List;
import java.util.UUID;

// Durable publish intent: one row per event, published marker written only after
// the broker confirms the send. The JDBC adapter implements it for Postgres.
public interface OutboxRepository {
    record DueRow(long outboxId, String eventId, UUID instructionId, String payload, int attempts,
            String traceparent, String tracestate) {}
    void insert(String eventId, UUID instructionId, String eventType, String payload,
            String traceparent, String tracestate);
    List<DueRow> dueBatch(int limit);
    void markPublished(long outboxId);
    void deferOnFailure(long outboxId, int attempts, java.time.Instant nextDue, String lastError);
    // Delivery derivation for reads: instruction IDs that already carry the
    // PUBLISHED marker. The published flag may lag a successful worker; reads
    // must never infer business completion from it.
    java.util.Set<java.util.UUID> publishedByInstruction(java.util.Collection<java.util.UUID> instructionIds);
    long countPending();
    long oldestPendingAgeMs();
    // Run summary support: [pending, published] outbox event counts for one runId.
    long[] countByRun(String runId);
    // Lab-wide variant: [pending, published] over all rows, no run join.
    long[] countLab();
}
