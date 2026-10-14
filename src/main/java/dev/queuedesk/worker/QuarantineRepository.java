package dev.queuedesk.worker;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Quarantine projection store (QUAR-02): observations of the backout queue,
// reconciled by browsing (never consuming). The MQ move and these rows are not
// atomic; readers pair items with the reconciler heartbeat for freshness.
public interface QuarantineRepository {
    record Item(String eventId, UUID instructionId, String reason, Instant firstSeenAt, Instant lastSeenAt) {}
    record Heartbeat(Instant lastReconciledAt, String lastError) {}
    void upsert(String digest, String eventId, UUID instructionId, String reason);
    // Resolve holds the broker no longer shows: rows whose digest is absent
    // from the current browse get resolved_at, so drained work stops reading
    // as QUARANTINED. An empty set resolves everything still open.
    void resolveExcept(Set<String> stillHeldDigests);
    List<Item> list(int limit);
    Set<UUID> quarantinedIds(java.util.Collection<UUID> instructionIds);
    void heartbeat(String lastError);
    Heartbeat readHeartbeat();
}
