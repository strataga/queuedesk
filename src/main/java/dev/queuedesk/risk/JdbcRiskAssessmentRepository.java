package dev.queuedesk.risk;

import dev.queuedesk.repository.PageCursor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcRiskAssessmentRepository implements RiskAssessmentRepository {
    private static final String COLUMNS = """
        assessment_id, instruction_id, feature_version, question_version, policy_version,
        input_hash, feature_snapshot, evaluation_status, policy_result, reason_codes,
        signals, provider_latency_ms, assessed_at, input_tokens, output_tokens""";

    private final JdbcClient jdbc;

    public JdbcRiskAssessmentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static StoredAssessment map(ResultSet rs, int row) throws SQLException {
        var codes = (String[]) rs.getArray("reason_codes").getArray();
        var latency = rs.getObject("provider_latency_ms", Long.class);
        return new StoredAssessment(rs.getObject("assessment_id", UUID.class),
                rs.getObject("instruction_id", UUID.class), rs.getString("feature_version"),
                rs.getString("question_version"), rs.getString("policy_version"),
                rs.getString("input_hash"), rs.getString("feature_snapshot"),
                rs.getString("evaluation_status"), rs.getString("policy_result"),
                codes == null ? List.of() : List.of(codes), rs.getString("signals"),
                latency, rs.getObject("assessed_at", OffsetDateTime.class).toInstant(),
                rs.getObject("input_tokens", Long.class), rs.getObject("output_tokens", Long.class));
    }

    @Override
    public Optional<StoredAssessment> find(UUID instructionId, String featureVersion, String questionVersion, String policyVersion) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM risk_assessment WHERE instruction_id = ?"
                        + " AND feature_version = ? AND question_version = ? AND policy_version = ?")
                .params(instructionId, featureVersion, questionVersion, policyVersion)
                .query(JdbcRiskAssessmentRepository::map).optional();
    }

    @Override
    public Optional<StoredAssessment> latestByInstruction(UUID instructionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM risk_assessment WHERE instruction_id = ?"
                        + " ORDER BY assessed_at DESC LIMIT 1")
                .param(instructionId).query(JdbcRiskAssessmentRepository::map).optional();
    }

    @Override
    public Map<UUID, StoredAssessment> latestByInstructions(Collection<UUID> instructionIds) {
        if (instructionIds.isEmpty()) return Map.of();
        var ids = List.copyOf(instructionIds);
        var out = new HashMap<UUID, StoredAssessment>();
        // DISTINCT ON keeps one row per instruction: the latest assessment.
        // Explicit IN list (bounded by page size): driver UUID-array mapping
        // is version-sensitive, placeholders are not.
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        jdbc.sql("SELECT DISTINCT ON (instruction_id) " + COLUMNS + " FROM risk_assessment"
                        + " WHERE instruction_id IN (" + placeholders + ") ORDER BY instruction_id, assessed_at DESC")
                .params(ids).query(JdbcRiskAssessmentRepository::map).list()
                .forEach(a -> out.put(a.instructionId(), a));
        return Map.copyOf(out);
    }

    @Override
    public void save(StoredAssessment assessment) {
        jdbc.sql("""
                INSERT INTO risk_assessment (assessment_id, instruction_id, feature_version,
                  question_version, policy_version, input_hash, feature_snapshot, evaluation_status,
                  policy_result, reason_codes, signals, provider_latency_ms, input_tokens, output_tokens)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?, ?)""")
                .params(assessment.assessmentId(), assessment.instructionId(), assessment.featureVersion(),
                        assessment.questionVersion(), assessment.policyVersion(), assessment.inputHash(),
                        assessment.featureSnapshotJson(), assessment.evaluationStatus(), assessment.policyResult(),
                        assessment.reasonCodes().toArray(String[]::new), assessment.signalsJson(),
                        assessment.providerLatencyMs(), assessment.inputTokens(), assessment.outputTokens())
                .update();
    }

    @Override
    public UsageTotals usageTotals() {
        return jdbc.sql("""
                SELECT COUNT(input_tokens) AS calls, COALESCE(SUM(input_tokens), 0) AS inputs,
                  COALESCE(SUM(output_tokens), 0) AS outputs FROM risk_assessment""")
                .query((rs, row) -> new UsageTotals(rs.getLong("calls"), rs.getLong("inputs"),
                        rs.getLong("outputs")))
                .single();
    }

    @Override
    public boolean acquireLease(UUID instructionId, String featureVersion, String questionVersion,
            String policyVersion, String owner, Instant until) {
        jdbc.sql("DELETE FROM risk_assessment_attempt WHERE status = 'OPEN' AND lease_until <= now()").update();
        int inserted = jdbc.sql("""
                INSERT INTO risk_assessment_attempt (instruction_id, feature_version, question_version,
                  policy_version, lease_owner, lease_until, status)
                VALUES (?, ?, ?, ?, ?, ?, 'OPEN') ON CONFLICT DO NOTHING""")
                .params(instructionId, featureVersion, questionVersion, policyVersion, owner,
                        OffsetDateTime.ofInstant(until, ZoneOffset.UTC))
                .update();
        return inserted == 1;
    }

    @Override
    public void completeLease(UUID instructionId, String featureVersion, String questionVersion, String policyVersion) {
        jdbc.sql("DELETE FROM risk_assessment_attempt WHERE instruction_id = ? AND feature_version = ?"
                        + " AND question_version = ? AND policy_version = ?")
                .params(instructionId, featureVersion, questionVersion, policyVersion).update();
    }

    @Override
    public void saveReview(Review review) {
        jdbc.sql("""
                INSERT INTO risk_review (review_id, instruction_id, actor, decision, reason, continuation_event_id)
                VALUES (?, ?, ?, ?, ?, ?)""")
                .params(review.reviewId(), review.instructionId(), review.actor(), review.decision(),
                        review.reason(), review.continuationEventId())
                .update();
    }

    @Override
    public Optional<Review> latestReview(UUID instructionId) {
        return jdbc.sql("""
                SELECT review_id, instruction_id, actor, decision, reason, continuation_event_id, recorded_at
                  FROM risk_review WHERE instruction_id = ? ORDER BY recorded_at DESC LIMIT 1""")
                .param(instructionId).query((rs, row) -> new Review(rs.getObject("review_id", UUID.class),
                        rs.getObject("instruction_id", UUID.class), rs.getString("actor"),
                        rs.getString("decision"), rs.getString("reason"),
                        rs.getString("continuation_event_id"),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public List<HeldRow> listHeld(java.util.Set<String> policyResults, String reasonCode, String query,
            int limit, Optional<PageCursor> cursor) {
        var sql = new StringBuilder("""
                SELECT a.instruction_id, i.client_reference, i.amount_minor, i.currency, i.state,
                       a.policy_result, a.reason_codes, a.assessed_at
                  FROM risk_assessment a JOIN instruction i ON i.instruction_id = a.instruction_id
                 WHERE 1=1""");
        var params = new ArrayList<>();
        if (!policyResults.isEmpty()) {
            sql.append(" AND a.policy_result = ANY(?)");
            params.add(policyResults.toArray(String[]::new));
        }
        if (reasonCode != null) {
            sql.append(" AND ? = ANY(a.reason_codes)");
            params.add(reasonCode);
        }
        if (query != null) {
            sql.append(" AND i.client_reference ILIKE ?");
            params.add("%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        cursor.ifPresent(c -> {
            sql.append(" AND (a.assessed_at, a.instruction_id) < (?, ?)");
            params.add(OffsetDateTime.ofInstant(c.createdAt(), ZoneOffset.UTC));
            params.add(c.instructionId());
        });
        sql.append(" ORDER BY a.assessed_at DESC, a.instruction_id DESC LIMIT ?");
        params.add(limit + 1);
        return jdbc.sql(sql.toString()).params(params).query((rs, row) -> {
            var codes = (String[]) rs.getArray("reason_codes").getArray();
            return new HeldRow(rs.getObject("instruction_id", UUID.class), rs.getString("client_reference"),
                    rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("state"),
                    rs.getString("policy_result"), codes == null ? List.of() : List.of(codes),
                    rs.getObject("assessed_at", OffsetDateTime.class).toInstant());
        }).list();
    }

    @Override
    public long countByState(String state) {
        return jdbc.sql("SELECT COUNT(*) FROM instruction WHERE state = ?").param(state).query(Long.class).single();
    }

    @Override
    public long countByResult(String policyResult) {
        return jdbc.sql("SELECT COUNT(*) FROM risk_assessment WHERE policy_result = ?")
                .param(policyResult).query(Long.class).single();
    }
}
