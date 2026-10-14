package dev.queuedesk.risk;

import dev.queuedesk.repository.PageCursor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Plain unit-test double: no Spring stereotype, constructed directly in tests.
public class InMemoryRiskAssessmentRepository implements RiskAssessmentRepository {
    private final Map<String, StoredAssessment> assessments = new HashMap<>();
    private final Map<String, Lease> leases = new HashMap<>();
    private final List<Review> reviews = new ArrayList<>();

    private record Lease(String owner, Instant until) {}

    private static String key(UUID instructionId, String fv, String qv, String pv) {
        return instructionId + "|" + fv + "|" + qv + "|" + pv;
    }

    @Override
    public synchronized Optional<StoredAssessment> find(UUID instructionId, String featureVersion,
            String questionVersion, String policyVersion) {
        return Optional.ofNullable(assessments.get(key(instructionId, featureVersion, questionVersion, policyVersion)));
    }

    @Override
    public synchronized Optional<StoredAssessment> latestByInstruction(UUID instructionId) {
        return assessments.values().stream().filter(a -> a.instructionId().equals(instructionId))
                .max(Comparator.comparing(StoredAssessment::assessedAt));
    }

    @Override
    public synchronized Map<UUID, StoredAssessment> latestByInstructions(Collection<UUID> instructionIds) {
        var out = new HashMap<UUID, StoredAssessment>();
        for (var id : instructionIds) latestByInstruction(id).ifPresent(a -> out.put(id, a));
        return Map.copyOf(out);
    }

    @Override
    public synchronized UsageTotals usageTotals() {
        var metered = assessments.values().stream().filter(a -> a.inputTokens() != null).toList();
        return new UsageTotals(metered.size(),
                metered.stream().mapToLong(a -> a.inputTokens()).sum(),
                metered.stream().mapToLong(a -> a.outputTokens() == null ? 0L : a.outputTokens()).sum());
    }

    @Override
    public synchronized void save(StoredAssessment assessment) {
        String key = key(assessment.instructionId(), assessment.featureVersion(),
                assessment.questionVersion(), assessment.policyVersion());
        // Mirror the identity unique constraint: a clash throws
        // DuplicateKeyException so the gate's reuse path triggers.
        if (assessments.containsKey(key))
            throw new org.springframework.dao.DuplicateKeyException("duplicate risk assessment identity: " + key);
        assessments.put(key, assessment);
    }

    @Override
    public synchronized boolean acquireLease(UUID instructionId, String featureVersion, String questionVersion,
            String policyVersion, String owner, Instant until) {
        leases.entrySet().removeIf(e -> e.getValue().until().isBefore(Instant.now()));
        return leases.putIfAbsent(key(instructionId, featureVersion, questionVersion, policyVersion),
                new Lease(owner, until)) == null;
    }

    @Override
    public synchronized void completeLease(UUID instructionId, String featureVersion, String questionVersion,
            String policyVersion) {
        leases.remove(key(instructionId, featureVersion, questionVersion, policyVersion));
    }

    @Override
    public synchronized void saveReview(Review review) { reviews.add(review); }

    @Override
    public synchronized Optional<Review> latestReview(UUID instructionId) {
        return reviews.stream().filter(r -> r.instructionId().equals(instructionId))
                .max(Comparator.comparing(Review::recordedAt));
    }

    @Override
    public synchronized List<HeldRow> listHeld(java.util.Set<String> policyResults, String reasonCode,
            String query, int limit, Optional<PageCursor> cursor) {
        throw new UnsupportedOperationException("listHeld needs the instruction join; integration tests cover it");
    }

    @Override
    public synchronized long countByState(String state) {
        throw new UnsupportedOperationException("integration tests cover it");
    }

    @Override
    public synchronized long countByResult(String policyResult) {
        return assessments.values().stream().filter(a -> a.policyResult().equals(policyResult)).count();
    }
}
