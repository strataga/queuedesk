package dev.queuedesk.worker;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Postgres implementation of {@link ProcessingAttemptRepository} with
 * explicit SQL. Joins the caller's transaction, which is exactly why
 * attempt rows from a rolled-back delivery vanish unless the caller writes
 * fresh evidence afterwards.
 */
@Repository
public class JdbcProcessingAttemptRepository implements ProcessingAttemptRepository {
    private final JdbcClient jdbc;
    public JdbcProcessingAttemptRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    private static Attempt map(ResultSet rs, int row) throws SQLException {
        return new Attempt(rs.getString("event_id"), rs.getString("result"), rs.getString("reason"));
    }
    @Override
    public void insert(UUID instructionId, String eventId, String result, String reason, Integer deliveryCount) {
        jdbc.sql("INSERT INTO processing_attempt (instruction_id, event_id, ended_at, result, reason, delivery_count) VALUES (?, ?, now(), ?, ?, ?)")
                .params(instructionId, eventId, result, reason, deliveryCount).update();
    }
    @Override
    public java.util.Set<UUID> withFailures(java.util.Collection<UUID> instructionIds) {
        if (instructionIds.isEmpty()) return java.util.Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(instructionIds.size(), "?"));
        return new java.util.HashSet<>(jdbc.sql(
                "SELECT DISTINCT instruction_id FROM processing_attempt WHERE instruction_id IN (" + placeholders + ") AND result IN ('FAILED','UNSUPPORTED')")
                .params(instructionIds.toArray()).query(UUID.class).list());
    }
    @Override
    public List<Attempt> forInstruction(UUID instructionId) {
        return jdbc.sql("SELECT event_id, result, reason FROM processing_attempt WHERE instruction_id = ? ORDER BY attempt_id")
                .param(instructionId).query(JdbcProcessingAttemptRepository::map).list();
    }
    @Override
    public long countResult(UUID instructionId, String result) {
        return jdbc.sql("SELECT COUNT(*) FROM processing_attempt WHERE instruction_id = ? AND result = ?")
                .params(instructionId, result).query(Long.class).single();
    }
    @Override
    public java.util.Optional<EventEvidence> latestForEvent(String eventId) {
        // The quarantine reason is the original failure, never the ROUTED marker.
        return jdbc.sql("SELECT instruction_id, reason FROM processing_attempt WHERE event_id = ? AND result IN ('FAILED','UNSUPPORTED') ORDER BY attempt_id DESC LIMIT 1")
                .param(eventId)
                .query((rs, row) -> new EventEvidence(rs.getObject("instruction_id", UUID.class), rs.getString("reason")))
                .optional();
    }
}
