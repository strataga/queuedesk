package dev.queuedesk.risk;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable risk state: assessments, provider-call leases, and audited
 * reviews.
 *
 * <p>Assessments are keyed by (instruction, feature version, question
 * version, policy version), so a redelivery under the same versions reuses
 * the stored verdict instead of re-calling the provider, while any version
 * bump legitimately re-evaluates. Leases ensure only one evaluation owns
 * the provider call at a time. Reviews are the audit trail for human
 * resume/reject decisions. The JDBC adapter implements this for Postgres.
 */
public interface RiskAssessmentRepository {
    /** One stored assessment: verdict, evidence (snapshot + signals JSON), versions, and metered usage. */
    record StoredAssessment(UUID assessmentId, UUID instructionId, String featureVersion,
            String questionVersion, String policyVersion, String inputHash, String featureSnapshotJson,
            String evaluationStatus, String policyResult, List<String> reasonCodes, String signalsJson,
            Long providerLatencyMs, Instant assessedAt, Long inputTokens, Long outputTokens) {}

    /** Budget-ledger totals over usage-bearing assessments. */
    record UsageTotals(long calls, long inputTokens, long outputTokens) {}

    /** One audited human review decision (RESUME or REJECT) on a held instruction. */
    record Review(UUID reviewId, UUID instructionId, String actor, String decision,
            String reason, String continuationEventId, Instant recordedAt) {}

    /** One row of the held-payments read: payment facts joined with the latest verdict. */
    record HeldRow(UUID instructionId, String clientReference, long amountMinor, String currency,
            String businessOutcome, String riskOutput, List<String> reasonCodes, Instant assessedAt) {}

    /** Finds the assessment for one instruction under exact versions (the reuse key). */
    Optional<StoredAssessment> find(UUID instructionId, String featureVersion, String questionVersion, String policyVersion);

    /** Finds the newest assessment for one instruction, any versions (read surfaces). */
    Optional<StoredAssessment> latestByInstruction(UUID instructionId);

    /** Batched {@link #latestByInstruction} for list reads: one query, no N+1. */
    Map<UUID, StoredAssessment> latestByInstructions(Collection<UUID> instructionIds);

    /**
     * The metered-usage ledger: calls counts usage-bearing rows only, so the
     * mock's nulls never inflate it. Feeds the budget-cap check and the
     * usage endpoint.
     */
    UsageTotals usageTotals();

    /**
     * Saves a new assessment. An identity conflict (same instruction and
     * versions) surfaces as a duplicate-key failure: the caller reuses the
     * winner and never overwrites stored evidence.
     */
    void save(StoredAssessment assessment);

    /**
     * Attempts to take the provider-call lease for one assessment identity.
     * Expired leases are reaped first; a live lease means another evaluation
     * is in flight.
     *
     * @return {@code true} when this caller now holds the call right
     */
    boolean acquireLease(UUID instructionId, String featureVersion, String questionVersion,
            String policyVersion, String owner, Instant until);

    /** Releases the lease for one assessment identity (completed, failed, or no longer needed). */
    void completeLease(UUID instructionId, String featureVersion, String questionVersion, String policyVersion);

    /** Appends one audited review decision. Reviews are never edited or deleted. */
    void saveReview(Review review);

    /** Finds the newest review for one instruction (the gate checks for an authorizing RESUME). */
    Optional<Review> latestReview(UUID instructionId);

    /**
     * Held-list read: newest first, optionally filtered by verdict set,
     * reason code, and a client-reference query. Returns up to
     * {@code limit + 1} rows so the caller can mint a next cursor.
     */
    List<HeldRow> listHeld(java.util.Set<String> policyResults, String reasonCode, String query,
            int limit, Optional<dev.queuedesk.repository.PageCursor> cursor);

    /** Counts instructions currently in one business state (summary support). */
    long countByState(String state);

    /** Counts assessments with one policy result (summary support). */
    long countByResult(String policyResult);
}
