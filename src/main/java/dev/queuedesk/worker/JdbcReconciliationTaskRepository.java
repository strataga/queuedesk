package dev.queuedesk.worker;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Postgres implementation of {@link ReconciliationTaskRepository} with
 * explicit SQL. {@code claimDue} selects with {@code SKIP LOCKED} and bumps
 * due times in the same statement, so even a future second poller cannot
 * claim (or double-resolve) the same task.
 */
@Repository
public class JdbcReconciliationTaskRepository implements ReconciliationTaskRepository {
    private final JdbcClient jdbc;
    public JdbcReconciliationTaskRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    private static Task map(ResultSet rs, int row) throws SQLException {
        return new Task(rs.getLong("task_id"), rs.getObject("instruction_id", UUID.class),
                rs.getString("event_id"), rs.getInt("attempts"),
                rs.getString("traceparent"), rs.getString("tracestate"));
    }
    @Override
    public void create(UUID instructionId, String eventId, String traceparent, String tracestate) {
        jdbc.sql("INSERT INTO reconciliation_task (instruction_id, event_id, traceparent, tracestate) VALUES (?, ?, ?, ?)")
                .params(instructionId, eventId, traceparent, tracestate).update();
    }
    @Override
    public List<Task> claimDue(int limit) {
        // Atomic claim: the SELECT ... FOR UPDATE SKIP LOCKED alone releases
        // its locks at commit, so a second poller could re-claim the same
        // OPEN rows before this poller settles them. Bumping next_due_at in
        // the same statement closes the window; the bump is a 5-minute lease —
        // settle/reschedule overwrites it, and a crashed poller only delays
        // the task instead of losing it.
        return jdbc.sql("""
                UPDATE reconciliation_task SET next_due_at = now() + make_interval(mins => 5)
                WHERE task_id IN (
                  SELECT task_id FROM reconciliation_task
                  WHERE status = 'OPEN' AND next_due_at <= now()
                  ORDER BY next_due_at LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING task_id, instruction_id, event_id, attempts, traceparent, tracestate""")
                .param(limit).query(JdbcReconciliationTaskRepository::map).list();
    }
    @Override
    public void reschedule(long taskId, int attempts, Instant nextDue, String error) {
        jdbc.sql("UPDATE reconciliation_task SET attempts = ?, next_due_at = ?, last_error = ?, updated_at = now() WHERE task_id = ?")
                .params(attempts, OffsetDateTime.ofInstant(nextDue, ZoneOffset.UTC), error, taskId).update();
    }
    @Override
    public void complete(long taskId, String error) {
        jdbc.sql("UPDATE reconciliation_task SET status = 'DONE', last_error = ?, updated_at = now() WHERE task_id = ?")
                .params(error, taskId).update();
    }
    @Override
    public Optional<Task> findOpen(UUID instructionId) {
        return jdbc.sql("SELECT task_id, instruction_id, event_id, attempts, traceparent, tracestate FROM reconciliation_task WHERE instruction_id = ? AND status = 'OPEN'")
                .param(instructionId).query(JdbcReconciliationTaskRepository::map).optional();
    }
    @Override
    public long countOpen() {
        return jdbc.sql("SELECT COUNT(*) FROM reconciliation_task WHERE status = 'OPEN'").query(Long.class).single();
    }
    @Override
    public long countOpenByRun(String runId) {
        return jdbc.sql("SELECT COUNT(*) FROM reconciliation_task t JOIN instruction i ON t.instruction_id = i.instruction_id WHERE t.status = 'OPEN' AND i.run_id = ?")
                .param(runId).query(Long.class).single();
    }
}
