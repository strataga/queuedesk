package dev.queuedesk.risk;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

// Policy composition without Spring (RISK-03): rules first, signals as
// context, REJECT only under the explicit simulation block, faults holding
// safely. No score or confidence ever allows, rejects, or names fraud.
class RiskPolicyTest {
    private static final Instant AS_OF = Instant.parse("2026-10-05T00:00:00Z");

    private FeatureSnapshot seen(double ratio) {
        return new FeatureSnapshot(50_000, 12, 60_000L, true, ratio, AS_OF, "1.0", false, false);
    }

    private FeatureSnapshot fresh() {
        return new FeatureSnapshot(50_000, 0, null, false, FeatureSnapshot.NO_BASELINE, AS_OF, "1.0", true, true);
    }

    private RiskSignalProvider.Assessment ok(RiskSignalProvider.ChoiceSignal... choices) {
        return new RiskSignalProvider.Assessment(UUID.randomUUID(), "hash",
                MockRiskSignalProvider.PROVIDER_VERSION, MockRiskSignalProvider.MODEL_VERSION,
                "mock-2026-10", List.of(choices), List.of(), 12, "OK", null);
    }

    private RiskSignalProvider.ChoiceSignal choice(String q, boolean answer) {
        double c = 0.96;
        return new RiskSignalProvider.ChoiceSignal(q, Boolean.toString(answer),
                Map.of("true", answer ? c : 1 - c, "false", answer ? 1 - c : c), c);
    }

    @Test void rulesOnlyAllowsQuietTraffic() {
        var decision = RiskPolicy.evaluate(seen(1.2), null);
        assertEquals("ALLOW", decision.policyResult());
        assertEquals("NOT_APPLICABLE", decision.evaluationStatus());
        assertTrue(decision.reasonCodes().isEmpty());
    }

    @Test void newRecipientHoldsForReview() {
        var decision = RiskPolicy.evaluate(fresh(), null);
        assertEquals("REVIEW", decision.policyResult());
        assertEquals(List.of(RiskPolicy.NEW_RECIPIENT), decision.reasonCodes());
    }

    @Test void unusualAmountHoldsForReview() {
        var decision = RiskPolicy.evaluate(seen(7.5), null);
        assertEquals("REVIEW", decision.policyResult());
        assertEquals(List.of(RiskPolicy.UNUSUAL_AMOUNT), decision.reasonCodes());
    }

    @Test void baselineEdgeIsNotUnusual() {
        assertFalse(RiskPolicy.unusualAmount(seen(4.999)));
        assertTrue(RiskPolicy.unusualAmount(seen(5.0)));
        assertFalse(RiskPolicy.unusualAmount(fresh()));
    }

    @Test void signalsNeverOverrideMatchedRules() {
        // Confident "all clear" answers are context; the matched rule holds.
        var assessment = ok(choice("Q01", false), choice("Q02", false));
        var decision = RiskPolicy.evaluate(fresh(), assessment);
        assertEquals("REVIEW", decision.policyResult());
        assertEquals("OK", decision.evaluationStatus());
    }

    @Test void quietSignalsAllowQuietTraffic() {
        var assessment = ok(choice("Q01", false), choice("Q02", false));
        var decision = RiskPolicy.evaluate(seen(1.1), assessment);
        assertEquals("ALLOW", decision.policyResult());
    }

    @Test void abstentionOnARequiredQuestionHoldsUnassessed() {
        var assessment = new RiskSignalProvider.Assessment(UUID.randomUUID(), "hash",
                MockRiskSignalProvider.PROVIDER_VERSION, MockRiskSignalProvider.MODEL_VERSION,
                "mock-2026-10", List.of(choice("Q01", false)), List.of("Q02"), 12, "OK", null);
        var decision = RiskPolicy.evaluate(seen(1.1), assessment);
        assertEquals("NOT_ASSESSED", decision.policyResult());
        assertEquals("UNKNOWN", decision.evaluationStatus());
    }

    @Test void rejectNeedsTheExplicitSimulationBlock() {
        var block = new FeatureSnapshot(9_600_000, 0, null, false, FeatureSnapshot.NO_BASELINE,
                AS_OF, "1.0", true, true);
        var decision = RiskPolicy.evaluate(block, null);
        assertEquals("REJECT", decision.policyResult());
        assertTrue(decision.reasonCodes().contains(RiskPolicy.SIMULATED_POLICY_BLOCK));
        // Same near-cap amount to a KNOWN receiver only reviews (unusual).
        var known = new FeatureSnapshot(9_600_000, 30, 5_000L, true, 96.0, AS_OF, "1.0", false, false);
        assertEquals("REVIEW", RiskPolicy.evaluate(known, null).policyResult());
        // A score alone never rejects: max-ratio traffic without a new
        // recipient reviews, and quiet traffic allows.
        assertEquals("REVIEW", RiskPolicy.evaluate(seen(500.0), null).policyResult());
    }

    @Test void faultsHoldSafelyWithTheirOwnStatus() {
        for (String status : List.of("TIMEOUT", "INVALID_RESPONSE", "PROVIDER_UNAVAILABLE")) {
            var decision = RiskPolicy.notAssessed(status);
            assertEquals("NOT_ASSESSED", decision.policyResult());
            assertEquals(status, decision.evaluationStatus());
        }
    }

    @Test void rulesStillEvaluateFirstOnFault() {
        // A fault never flips or hides a deterministic rule match: the policy
        // result is identical with or without the outage; only the evaluation
        // status records the fault. Quiet traffic holds NOT_ASSESSED.
        for (String status : List.of("TIMEOUT", "INVALID_RESPONSE", "PROVIDER_UNAVAILABLE")) {
            var review = RiskPolicy.evaluateOnFault(fresh(), status);
            assertEquals("REVIEW", review.policyResult());
            assertEquals(List.of(RiskPolicy.NEW_RECIPIENT), review.reasonCodes());
            assertEquals(status, review.evaluationStatus());
            var unusual = RiskPolicy.evaluateOnFault(seen(7.5), status);
            assertEquals("REVIEW", unusual.policyResult());
            assertEquals(status, unusual.evaluationStatus());
            var quiet = RiskPolicy.evaluateOnFault(seen(1.2), status);
            assertEquals("NOT_ASSESSED", quiet.policyResult());
            assertEquals(status, quiet.evaluationStatus());
        }
    }

    @Test void ruleDetailsArePredefinedText() {
        var snapshot = seen(7.5);
        assertEquals("recipient_seen_before = false", RiskPolicy.ruleDetail(RiskPolicy.NEW_RECIPIENT, snapshot));
        assertTrue(RiskPolicy.ruleDetail(RiskPolicy.UNUSUAL_AMOUNT, snapshot).contains("7.5"));
        assertThrows(IllegalArgumentException.class, () -> RiskPolicy.ruleDetail("MADE_UP", snapshot));
    }

    @Test void snapshotRejectsInconsistentMissingness() {
        assertThrows(IllegalArgumentException.class, () -> new FeatureSnapshot(1, 0, 5L, false,
                FeatureSnapshot.NO_BASELINE, AS_OF, "1.0", true, true));
        assertThrows(IllegalArgumentException.class, () -> new FeatureSnapshot(1, 0, null, false,
                1.0, AS_OF, "1.0", true, true));
        assertThrows(IllegalArgumentException.class, () -> new FeatureSnapshot(1, 0, null, false,
                FeatureSnapshot.NO_BASELINE, AS_OF, "1.0", false, true));
    }
}
