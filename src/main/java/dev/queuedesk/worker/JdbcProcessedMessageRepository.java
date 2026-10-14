package dev.queuedesk.worker;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

// Postgres adapter: explicit SQL. All calls join the caller's transaction — the
// worker's or a test's — so dedup and outcome commit or roll back together.
@Repository
public class JdbcProcessedMessageRepository implements ProcessedMessageRepository {
    private final JdbcClient jdbc;
    public JdbcProcessedMessageRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    private static Handled map(ResultSet rs, int row) throws SQLException {
        return new Handled(rs.getObject("instruction_id", UUID.class),
                rs.getObject("handled_at", OffsetDateTime.class).toInstant(),
                rs.getString("outcome_reference"));
    }
    @Override
    public Optional<Handled> find(String eventId) {
        return jdbc.sql("SELECT instruction_id, handled_at, outcome_reference FROM processed_message WHERE event_id = ?")
                .param(eventId).query(JdbcProcessedMessageRepository::map).optional();
    }
    @Override
    public void insert(String eventId, UUID instructionId, String outcomeReference) {
        jdbc.sql("INSERT INTO processed_message (event_id, instruction_id, outcome_reference) VALUES (?, ?, ?)")
                .params(eventId, instructionId, outcomeReference).update();
    }
    @Override
    public long countForInstruction(UUID instructionId) {
        return jdbc.sql("SELECT COUNT(*) FROM processed_message WHERE instruction_id = ?")
                .param(instructionId).query(Long.class).single();
    }
}
