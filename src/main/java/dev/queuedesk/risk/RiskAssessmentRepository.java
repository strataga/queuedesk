package dev.queuedesk.risk;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Durable risk state (RISK-01..04): assessments keyed by (instruction, frozen
// versions), attempt leases guarding concurrent provider calls, and audited
// reviews. The JDBC adapter implements it for Postgres.
public interface RiskAssessmentRepository {
    record StoredAssessment(UUID assessmentId, UUID instructionId, String featureVersion,
            String questionVersion, String policyVersion, String inputHash, String featureSnapshotJson,
            String evaluationStatus, String policyResult, List<String> reasonCodes, String signalsJson,
            Long providerLatencyMs, Instant assessedAt, Long inputTokens, Long outputTokens) {}

    record UsageTotals(long calls, long inputTokens, long outputTokens) {}

    record Review(UUID reviewId, UUID instructionId, String actor, String decision,
            String reason, String continuationEventId, Instant recordedAt) {}

    record HeldRow(UUID instructionId, String clientReference, long amountMinor, String currency,
            String businessOutcome, String riskOutput, List<String> reasonCodes, Instant assessedAt) {}

    Optional<StoredAssessment> find(UUID instructionId, String featureVersion, String questionVersion, String policyVersion);
    Optional<StoredAssessment> latestByInstruction(UUID instructionId);
    Map<UUID, StoredAssessment> latestByInstructions(Collection<UUID> instructionIds);
    // Metered-usage ledger: calls counts usage-bearing rows only (the mock's
    // nulls never inflate it). Feeds the $1-cap check and the usage endpoint.
    UsageTotals usageTotals();
    // Identity conflict (same instruction + versions) surfaces as
    // DuplicateKeyException: the caller reuses the winner, never overwrites.
    void save(StoredAssessment assessment);
    // Attempt lease: true when this caller holds the call right. Expired OPEN
    // rows are reaped first; a live OPEN row means another call is in flight.
    boolean acquireLease(UUID instructionId, String featureVersion, String questionVersion,
            String policyVersion, String owner, Instant until);
    void completeLease(UUID instructionId, String featureVersion, String questionVersion, String policyVersion);
    void saveReview(Review review);
    Optional<Review> latestReview(UUID instructionId);
    List<HeldRow> listHeld(java.util.Set<String> policyResults, String reasonCode, String query,
            int limit, Optional<dev.queuedesk.repository.PageCursor> cursor);
    long countByState(String state);
    long countByResult(String policyResult);
}
