package dev.queuedesk.worker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Plain unit-test double: no Spring stereotype, constructed directly in tests.
// Mirrors the JDBC adapter's observable contract: unresolved-only reads in
// last-seen order with real timestamps, resolve/reopen lifecycle included.
public class InMemoryQuarantineRepository implements QuarantineRepository {
    private static final class Row {
        String digest;
        String eventId;
        UUID instructionId;
        String reason;
        Instant firstSeen;
        Instant lastSeen;
        boolean resolved;
        Row(String digest, String eventId, UUID instructionId, String reason, Instant now) {
            this.digest = digest;
            this.eventId = eventId;
            this.instructionId = instructionId;
            this.reason = reason;
            this.firstSeen = now;
            this.lastSeen = now;
        }
    }
    private final List<Row> rows = new ArrayList<>();
    private Heartbeat heartbeat = new Heartbeat(null, null);
    @Override
    public synchronized void upsert(String digest, String eventId, UUID instructionId, String reason) {
        var now = Instant.now();
        for (var r : rows) {
            if (!r.digest.equals(digest)) continue;
            if (eventId != null) r.eventId = eventId;
            if (instructionId != null) r.instructionId = instructionId;
            if (r.reason.contains("pending")) r.reason = reason;
            else if (!reason.contains("pending")) r.reason = reason;
            r.lastSeen = now;
            r.resolved = false;
            return;
        }
        rows.add(new Row(digest, eventId, instructionId, reason, now));
    }
    @Override
    public synchronized void resolveExcept(Set<String> stillHeldDigests) {
        var now = Instant.now();
        for (var r : rows) {
            if (!r.resolved && !stillHeldDigests.contains(r.digest)) {
                r.resolved = true;
                r.lastSeen = now;
            }
        }
    }
    @Override
    public synchronized List<Item> list(int limit) {
        return rows.stream().filter(r -> !r.resolved)
                .sorted(Comparator.comparing((Row r) -> r.lastSeen).reversed())
                .limit(limit)
                .map(r -> new Item(r.eventId, r.instructionId, r.reason, r.firstSeen, r.lastSeen)).toList();
    }
    @Override
    public synchronized Set<UUID> quarantinedIds(java.util.Collection<UUID> instructionIds) {
        var wanted = new HashSet<>(instructionIds);
        var done = new HashSet<UUID>();
        for (var r : rows) if (!r.resolved && r.instructionId != null && wanted.contains(r.instructionId)) done.add(r.instructionId);
        return done;
    }
    @Override
    public synchronized void heartbeat(String lastError) { heartbeat = new Heartbeat(java.time.Instant.now(), JdbcQuarantineRepository.truncate(lastError)); }
    @Override
    public synchronized Heartbeat readHeartbeat() { return heartbeat; }
}
