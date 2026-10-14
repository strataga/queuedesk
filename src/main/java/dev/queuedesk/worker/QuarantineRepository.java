package dev.queuedesk.worker;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Quarantine projection store: database observations of what the backout
 * queue currently holds.
 *
 * <p>The projection is reconciled by browsing the queue, never by consuming
 * from it, so observing held work cannot disturb it. The MQ move and these
 * rows are not atomic; readers must pair the items with the reconciler
 * {@link Heartbeat} to judge freshness. Identity is the message-body
 * digest, which stays stable even for messages too broken to parse an
 * event ID from.
 */
public interface QuarantineRepository {
    /** One observed held message and the best-known reason it is held. */
    record Item(String eventId, UUID instructionId, String reason, Instant firstSeenAt, Instant lastSeenAt) {}
    /** When the projection was last reconciled, and the last failure (bounded for API safety). */
    record Heartbeat(Instant lastReconciledAt, String lastError) {}

    /**
     * Records or refreshes one held message by body digest. A specific
     * failure reason is never overwritten by a generic placeholder, and a
     * redelivered digest reopens a previously resolved row.
     */
    void upsert(String digest, String eventId, UUID instructionId, String reason);

    /**
     * Resolves holds the broker no longer shows: rows whose digest is absent
     * from the current complete browse are closed, so drained work stops
     * reading as quarantined. An empty set resolves everything still open.
     */
    void resolveExcept(Set<String> stillHeldDigests);

    /** Lists unresolved holds, most recently seen first. */
    List<Item> list(int limit);

    /** Delivery derivation for reads: which of these IDs currently have an unresolved hold. */
    Set<UUID> quarantinedIds(java.util.Collection<UUID> instructionIds);

    /** Stamps a reconciliation pass; {@code lastError} is {@code null} on a clean pass. */
    void heartbeat(String lastError);

    /** Reads the current freshness heartbeat. */
    Heartbeat readHeartbeat();
}
