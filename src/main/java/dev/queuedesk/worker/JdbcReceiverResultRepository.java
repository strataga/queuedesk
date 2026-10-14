package dev.queuedesk.worker;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

/**
 * Postgres implementation of {@link ReceiverResultRepository} with explicit
 * SQL. Joins the gateway's own transaction, which is deliberately separate
 * from the worker's outcome transaction.
 */
@Repository
public class JdbcReceiverResultRepository implements ReceiverResultRepository {
    private final JdbcClient jdbc;
    public JdbcReceiverResultRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override
    public Optional<Row> find(UUID instructionId) {
        return jdbc.sql("SELECT outcome, reason, calls FROM receiver_result WHERE instruction_id = ?")
                .param(instructionId)
                .query((rs, row) -> new Row(rs.getString("outcome"), rs.getString("reason"), rs.getInt("calls")))
                .optional();
    }
    @Override
    public void insert(UUID instructionId, String outcome, String reason) {
        jdbc.sql("INSERT INTO receiver_result (instruction_id, outcome, reason) VALUES (?, ?, ?)")
                .params(instructionId, outcome, reason).update();
    }
    @Override
    public void complete(UUID instructionId, String outcome, String reason) {
        jdbc.sql("UPDATE receiver_result SET outcome = ?, reason = ?, calls = calls + 1, updated_at = now() WHERE instruction_id = ?")
                .params(outcome, reason, instructionId).update();
    }
    @Override
    public void touch(UUID instructionId) {
        jdbc.sql("UPDATE receiver_result SET calls = calls + 1, updated_at = now() WHERE instruction_id = ?")
                .param(instructionId).update();
    }
}
