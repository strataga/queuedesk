package dev.queuedesk.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

// Postgres adapter: explicit SQL. insert() joins the admission transaction; marking
// and deferral each run in their own short transaction owned by the publisher.
@Repository
public class JdbcOutboxRepository implements OutboxRepository {
    private final JdbcClient jdbc;
    public JdbcOutboxRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    private static DueRow map(ResultSet rs, int row) throws SQLException {
        return new DueRow(rs.getLong("outbox_id"), rs.getString("event_id"),
                rs.getObject("instruction_id", UUID.class), rs.getString("payload"),
                rs.getInt("publish_attempts"), rs.getString("traceparent"), rs.getString("tracestate"));
    }
    @Override
    public void insert(String eventId, UUID instructionId, String eventType, String payload,
            String traceparent, String tracestate) {
        jdbc.sql("INSERT INTO outbox (event_id, instruction_id, event_type, payload, traceparent, tracestate) VALUES (?, ?, ?, ?, ?, ?)")
                .params(eventId, instructionId, eventType, payload, traceparent, tracestate).update();
    }
    @Override
    public List<DueRow> dueBatch(int limit) {
        // Strict id order across batches, not just within one: after a defer,
        // later rows must wait for the deferred head instead of publishing
        // ahead of it. The head-of-line stall is bounded by the backoff the
        // defer carries; publisher failures here are broker-level, so a lone
        // poison head cannot wedge followers for longer than its next due.
        return jdbc.sql("""
                SELECT outbox_id, event_id, instruction_id, payload, publish_attempts, traceparent, tracestate FROM outbox
                WHERE published_at IS NULL AND next_due_at <= now()
                  AND outbox_id < COALESCE((SELECT MIN(outbox_id) FROM outbox
                    WHERE published_at IS NULL AND next_due_at > now()), 9223372036854775807)
                ORDER BY outbox_id ASC LIMIT ? FOR UPDATE SKIP LOCKED""")
                .param(limit).query(JdbcOutboxRepository::map).list();
    }
    @Override
    public void markPublished(long outboxId) {
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE outbox_id = ? AND published_at IS NULL")
                .param(outboxId).update();
    }
    @Override
    public void deferOnFailure(long outboxId, int attempts, java.time.Instant nextDue, String lastError) {
        jdbc.sql("UPDATE outbox SET publish_attempts = ?, next_due_at = ?, last_error = ? WHERE outbox_id = ?")
                .params(attempts, OffsetDateTime.ofInstant(nextDue, ZoneOffset.UTC), lastError, outboxId).update();
    }
    @Override
    public java.util.Set<java.util.UUID> publishedByInstruction(java.util.Collection<java.util.UUID> instructionIds) {
        if (instructionIds.isEmpty()) return java.util.Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(instructionIds.size(), "?"));
        return new java.util.HashSet<>(jdbc.sql(
                "SELECT instruction_id FROM outbox WHERE published_at IS NOT NULL AND instruction_id IN (" + placeholders + ")")
                .params(instructionIds.toArray()).query(UUID.class).list());
    }
    @Override
    public long countPending() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
    }
    @Override
    public long oldestPendingAgeMs() {
        Long ms = jdbc.sql("SELECT EXTRACT(EPOCH FROM (now() - MIN(created_at))) * 1000 FROM outbox WHERE published_at IS NULL")
                .query(Long.class).optional().orElse(null);
        return ms == null ? 0 : ms;
    }
    @Override
    public long[] countByRun(String runId) {
        return jdbc.sql("""
                SELECT COUNT(*) FILTER (WHERE o.published_at IS NULL) AS pending,
                       COUNT(*) FILTER (WHERE o.published_at IS NOT NULL) AS published
                FROM outbox o JOIN instruction i ON o.instruction_id = i.instruction_id
                WHERE i.run_id = ?""")
                .param(runId).query((rs, row) -> new long[]{rs.getLong("pending"), rs.getLong("published")}).single();
    }
    @Override
    public long[] countLab() {
        return jdbc.sql("""
                SELECT COUNT(*) FILTER (WHERE published_at IS NULL) AS pending,
                       COUNT(*) FILTER (WHERE published_at IS NOT NULL) AS published
                FROM outbox""")
                .query((rs, row) -> new long[]{rs.getLong("pending"), rs.getLong("published")}).single();
    }
}
