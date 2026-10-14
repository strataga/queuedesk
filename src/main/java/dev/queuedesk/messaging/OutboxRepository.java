package dev.queuedesk.messaging;

import java.util.List;
import java.util.UUID;

/**
 * Durable publish intent: one row per event, written in the same
 * transaction as the instruction it announces.
 *
 * <p>The published marker is written only after the broker confirms the
 * send, never before. An unmarked row therefore always means "still owed
 * delivery", across restarts and outages. The JDBC adapter implements this
 * contract for Postgres; an in-memory adapter mirrors it for unit tests.
 */
public interface OutboxRepository {
    /** One unpublished event that is due for sending, with its stored trace context. */
    record DueRow(long outboxId, String eventId, UUID instructionId, String payload, int attempts,
            String traceparent, String tracestate) {}

    /** Records publish intent for a new event. Joins the caller's admission transaction. */
    void insert(String eventId, UUID instructionId, String eventType, String payload,
            String traceparent, String tracestate);

    /**
     * Returns the next due batch in strict ID order. Followers wait behind a
     * deferred head row, so events are never published out of order.
     */
    List<DueRow> dueBatch(int limit);

    /** Marks one event published. Called only after the broker confirmed the send. */
    void markPublished(long outboxId);

    /** Defers one event after a failed send, recording the attempt count and last error as evidence. */
    void deferOnFailure(long outboxId, int attempts, java.time.Instant nextDue, String lastError);

    /**
     * Delivery derivation for reads: which of these instructions already
     * carry the published marker. The flag may lag a successful worker;
     * reads must never infer business completion from it.
     */
    java.util.Set<java.util.UUID> publishedByInstruction(java.util.Collection<java.util.UUID> instructionIds);

    /** Counts events still owed delivery. */
    long countPending();

    /** Age in milliseconds of the oldest unpublished event, or 0 when the outbox is drained. */
    long oldestPendingAgeMs();

    /** Run summary support: {@code [pending, published]} event counts for one run. */
    long[] countByRun(String runId);

    /** Lab-wide variant of {@link #countByRun}: {@code [pending, published]} over all rows. */
    long[] countLab();
}
