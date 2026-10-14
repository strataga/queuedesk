package dev.queuedesk.repository;

import dev.queuedesk.domain.PaymentInstruction;
import dev.queuedesk.domain.RequestHash;
import dev.queuedesk.domain.StoredInstruction;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Postgres implementation of {@link InstructionRepository} with explicit SQL
 * and no ORM.
 *
 * <p>{@link #save} writes the instruction row plus its opening audit row;
 * the caller (the service) owns the transaction boundary, so the two
 * inserts commit or roll back together. The same pattern applies to
 * {@link #transition}: the state update is conditional on the expected
 * current state, and the audit row only follows a successful move.
 *
 * <p>Dynamic {@code WHERE} clauses are assembled only from fixed fragments
 * written in this class; every caller-supplied value travels as a bound
 * parameter.
 */
@Repository
public class JdbcInstructionRepository implements InstructionRepository {
    private static final String COLUMNS = """
        instruction_id, idempotency_key, correlation_id, client_reference, run_id,
        sender_participant, receiver_participant, amount_minor, currency,
        created_at, updated_at, state, reason, version""";
    private final JdbcClient jdbc;
    public JdbcInstructionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    private static StoredInstruction map(ResultSet rs, int row) throws SQLException {
        var payload = new PaymentInstruction(rs.getString("client_reference"), rs.getString("run_id"),
                rs.getString("sender_participant"), rs.getString("receiver_participant"),
                rs.getLong("amount_minor"), rs.getString("currency"));
        return new StoredInstruction(rs.getObject("instruction_id", UUID.class), rs.getString("idempotency_key"),
                rs.getString("correlation_id"), payload,
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getString("state"), rs.getString("reason"), rs.getLong("version"));
    }
    @Override
    public Optional<StoredInstruction> byKey(String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM instruction WHERE idempotency_key = ?")
                .param(key).query(JdbcInstructionRepository::map).optional();
    }
    @Override
    public Optional<StoredInstruction> byId(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM instruction WHERE instruction_id = ?")
                .param(id).query(JdbcInstructionRepository::map).optional();
    }
    @Override
    public List<StoredInstruction> all() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM instruction ORDER BY created_at, instruction_id")
                .query(JdbcInstructionRepository::map).list();
    }
    @Override
    public List<StoredInstruction> recent(int limit) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM instruction ORDER BY created_at DESC, instruction_id DESC LIMIT ?")
                .param(limit).query(JdbcInstructionRepository::map).list();
    }
    @Override
    public void save(StoredInstruction i) {
        jdbc.sql("""
                INSERT INTO instruction (instruction_id, idempotency_key, request_hash, run_id,
                  client_reference, sender_participant, receiver_participant, amount_minor,
                  currency, state, reason, correlation_id, created_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(Arrays.asList(i.instructionId(), i.idempotencyKey(), RequestHash.of(i.payload()),
                        i.payload().runId(), i.payload().clientReference(), i.payload().senderParticipant(),
                        i.payload().receiverParticipant(), i.payload().amountMinor(), i.payload().currency(),
                        i.state(), i.reason(), i.correlationId(),
                        OffsetDateTime.ofInstant(i.receivedAt(), ZoneOffset.UTC), i.version()))
                .update();
        jdbc.sql("INSERT INTO instruction_audit (instruction_id, old_state, new_state, reason, actor) VALUES (?, NULL, ?, ?, 'admission')")
                .params(i.instructionId(), i.state(), i.reason()).update();
    }
    @Override
    public boolean transition(UUID id, String from, String to, String reason, String actor) {
        int moved = jdbc.sql("""
                UPDATE instruction SET state = ?, reason = ?, version = version + 1, updated_at = now()
                WHERE instruction_id = ? AND state = ?""")
                .params(to, reason, id, from).update();
        if (moved == 0) return false;
        jdbc.sql("INSERT INTO instruction_audit (instruction_id, old_state, new_state, reason, actor) VALUES (?, ?, ?, ?, ?)")
                .params(id, from, to, reason, actor).update();
        return true;
    }
    @Override
    public java.util.Map<String, Long> countByState(String runId) {
        var counts = new java.util.LinkedHashMap<String, Long>();
        jdbc.sql("SELECT state, COUNT(*) AS n FROM instruction WHERE run_id = ? GROUP BY state")
                .param(runId).query((rs, row) -> counts.put(rs.getString("state"), rs.getLong("n"))).list();
        return java.util.Map.copyOf(counts);
    }
    @Override
    public java.util.Map<String, Long> countLabByState(java.time.Instant since, java.time.Instant until) {
        var sql = new StringBuilder("SELECT state, COUNT(*) AS n FROM instruction WHERE 1=1");
        var params = new java.util.ArrayList<>();
        if (since != null) { sql.append(" AND created_at >= ?"); params.add(java.time.OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC)); }
        if (until != null) { sql.append(" AND created_at <= ?"); params.add(java.time.OffsetDateTime.ofInstant(until, java.time.ZoneOffset.UTC)); }
        sql.append(" GROUP BY state");
        var counts = new java.util.LinkedHashMap<String, Long>();
        jdbc.sql(sql.toString()).params(params.toArray())
                .query((rs, row) -> counts.put(rs.getString("state"), rs.getLong("n"))).list();
        return java.util.Map.copyOf(counts);
    }
    @Override
    public java.util.Map<java.time.Instant, Long> completionsPerBucket(java.time.Instant since,
            java.time.Instant until, long bucketSeconds) {
        var sql = new StringBuilder(
                "SELECT to_timestamp(floor(extract(epoch from updated_at) / ?) * ?) AS bucket, COUNT(*) AS n"
                + " FROM instruction WHERE state = 'COMPLETED_SIMULATED'");
        var params = new java.util.ArrayList<>();
        params.add(bucketSeconds);
        params.add(bucketSeconds);
        if (since != null) { sql.append(" AND updated_at >= ?"); params.add(java.time.OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC)); }
        if (until != null) { sql.append(" AND updated_at <= ?"); params.add(java.time.OffsetDateTime.ofInstant(until, java.time.ZoneOffset.UTC)); }
        sql.append(" GROUP BY 1");
        var buckets = new java.util.LinkedHashMap<java.time.Instant, Long>();
        jdbc.sql(sql.toString()).params(params.toArray())
                .query((rs, row) -> buckets.put(
                        rs.getObject("bucket", java.time.OffsetDateTime.class).toInstant(),
                        rs.getLong("n"))).list();
        return java.util.Map.copyOf(buckets);
    }
    @Override
    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM instruction").query(Long.class).single();
    }
    @Override
    public void lockForAdmission() {
        // Void-returning lock call: execute and discard the single empty row.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('queuedesk-admit-cap'))")
                .query((rs, row) -> null).list();
    }
    @Override
    public List<StoredInstruction> list(String runId, String status, int limit, Optional<PageCursor> cursor,
            Instant since, Instant until, boolean newestFirst) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM instruction WHERE 1=1");
        var params = new ArrayList<>();
        if (runId != null) { sql.append(" AND run_id = ?"); params.add(runId); }
        if (status != null) { sql.append(" AND state = ?"); params.add(status); }
        if (since != null) { sql.append(" AND created_at >= ?"); params.add(OffsetDateTime.ofInstant(since, ZoneOffset.UTC)); }
        if (until != null) { sql.append(" AND created_at <= ?"); params.add(OffsetDateTime.ofInstant(until, ZoneOffset.UTC)); }
        cursor.ifPresent(c -> {
            sql.append(newestFirst ? " AND (created_at, instruction_id) < (?, ?)"
                    : " AND (created_at, instruction_id) > (?, ?)");
            params.add(OffsetDateTime.ofInstant(c.createdAt(), ZoneOffset.UTC));
            params.add(c.instructionId());
        });
        sql.append(newestFirst ? " ORDER BY created_at DESC, instruction_id DESC LIMIT ?"
                : " ORDER BY created_at ASC, instruction_id ASC LIMIT ?");
        params.add(limit + 1);
        return jdbc.sql(sql.toString()).params(params).query(JdbcInstructionRepository::map).list();
    }
}
