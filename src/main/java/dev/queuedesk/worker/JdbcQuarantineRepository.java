package dev.queuedesk.worker;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Postgres implementation of {@link QuarantineRepository} with explicit SQL.
 * Joins the reconciler's transaction, so one browse lands (upserts,
 * resolutions, and heartbeat) as a single consistent projection update.
 */
@Repository
public class JdbcQuarantineRepository implements QuarantineRepository {
    private final JdbcClient jdbc;
    public JdbcQuarantineRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    private static Item map(ResultSet rs, int row) throws SQLException {
        return new Item(rs.getString("event_id"), rs.getObject("instruction_id", UUID.class),
                rs.getString("reason"),
                rs.getObject("first_seen_at", OffsetDateTime.class).toInstant(),
                rs.getObject("last_seen_at", OffsetDateTime.class).toInstant());
    }
    @Override
    public void upsert(String digest, String eventId, UUID instructionId, String reason) {
        // A specific reason never regresses to the generic pending placeholder.
        jdbc.sql("""
                INSERT INTO quarantine_record (message_digest, event_id, instruction_id, reason)
                VALUES (?, ?, ?, ?) ON CONFLICT (message_digest) DO UPDATE SET
                  event_id = COALESCE(EXCLUDED.event_id, quarantine_record.event_id),
                  instruction_id = COALESCE(EXCLUDED.instruction_id, quarantine_record.instruction_id),
                  reason = CASE WHEN quarantine_record.reason LIKE '%pending%' THEN EXCLUDED.reason
                                WHEN EXCLUDED.reason LIKE '%pending%' THEN quarantine_record.reason
                                ELSE EXCLUDED.reason END,
                  last_seen_at = now(),
                  resolved_at = NULL""")
                .params(digest, eventId, instructionId, reason).update();
    }
    @Override
    public void resolveExcept(Set<String> stillHeldDigests) {
        // Clearing resolved_at is NOT offered: a redelivered digest reopens
        // via upsert, which must reset the resolution (see below).
        if (stillHeldDigests.isEmpty()) {
            jdbc.sql("UPDATE quarantine_record SET resolved_at = now() WHERE resolved_at IS NULL").update();
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(stillHeldDigests.size(), "?"));
        jdbc.sql("UPDATE quarantine_record SET resolved_at = now() WHERE resolved_at IS NULL AND message_digest NOT IN ("
                + placeholders + ")").params(stillHeldDigests.toArray()).update();
    }
    @Override
    public List<Item> list(int limit) {
        return jdbc.sql("SELECT event_id, instruction_id, reason, first_seen_at, last_seen_at FROM quarantine_record WHERE resolved_at IS NULL ORDER BY last_seen_at DESC LIMIT ?")
                .param(limit).query(JdbcQuarantineRepository::map).list();
    }
    @Override
    public Set<UUID> quarantinedIds(java.util.Collection<UUID> instructionIds) {
        if (instructionIds.isEmpty()) return Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(instructionIds.size(), "?"));
        return new HashSet<>(jdbc.sql(
                "SELECT instruction_id FROM quarantine_record WHERE resolved_at IS NULL AND instruction_id IN (" + placeholders + ")")
                .params(instructionIds.toArray()).query(UUID.class).list());
    }
    @Override
    public void heartbeat(String lastError) {
        // Bound what leaves the building: first line, 240 chars. Full broker
        // stack text stays in the server logs, not in the API-visible view.
        jdbc.sql("UPDATE quarantine_projection_meta SET last_reconciled_at = now(), last_error = ? WHERE singleton")
                .param(truncate(lastError)).update();
    }
    static String truncate(String lastError) {
        if (lastError == null) return null;
        int line = lastError.indexOf('\n');
        String first = line < 0 ? lastError : lastError.substring(0, line);
        return first.length() <= 240 ? first : first.substring(0, 240);
    }
    @Override
    public Heartbeat readHeartbeat() {
        return jdbc.sql("SELECT last_reconciled_at, last_error FROM quarantine_projection_meta WHERE singleton")
                .query((rs, row) -> {
                    var at = rs.getObject("last_reconciled_at", OffsetDateTime.class);
                    return new Heartbeat(at == null ? null : at.toInstant(), rs.getString("last_error"));
                }).single();
    }
}
