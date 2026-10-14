package dev.queuedesk.risk;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

// Deterministic mock provider without Spring (RISK-02): feature-mirrored
// answers, seeded confidence, bounded latency, and the fault taxonomy. The
// mock never meters usage (null on every assessment) — only Jev rows carry
// token counts into the $1-cap ledger.
class MockRiskSignalProviderTest {
    private static final Instant AS_OF = Instant.parse("2026-10-05T00:00:00Z");

    private static MockRiskSignalProvider provider() {
        return new MockRiskSignalProvider(RiskProperties.forTest(RiskMode.RULES_PLUS_MOCK));
    }

    private static FeatureSnapshot snapshot() {
        return new FeatureSnapshot(50_000, 0, null, false, FeatureSnapshot.NO_BASELINE,
                AS_OF, "1.0", true, true);
    }

    @Test void answersMirrorSnapshotFeatures() throws Exception {
        var assessment = provider().assess(snapshot(), "hash-1");
        assertEquals("true", answer(assessment, "Q01"));
        assertEquals(Boolean.toString(RiskPolicy.unusualAmount(snapshot())), answer(assessment, "Q02"));
        assertEquals(List.of(), assessment.noulQuestionIds());
        assertDoesNotThrow(() -> RiskSignalProvider.validate(assessment, "mock-2026-10"));
        assertEquals("OK", assessment.evaluationStatus());
    }

    @Test void validationRejectsMalformedResponses() {
        var broken = new RiskSignalProvider.Assessment(UUID.randomUUID(), "hash",
                MockRiskSignalProvider.PROVIDER_VERSION, MockRiskSignalProvider.MODEL_VERSION,
                "mock-2026-10",
                List.of(new RiskSignalProvider.ChoiceSignal("Q01", "true", Map.of("true", 0.5), 0.5)),
                List.of(), 5, "OK", null);
        assertThrows(RiskSignalProvider.InvalidResponse.class,
                () -> RiskSignalProvider.validate(broken, "mock-2026-10"));
    }

    @Test void sameInputHashReplaysIdenticalChoices() throws Exception {
        var provider = provider();
        var first = provider.assess(snapshot(), "stable-hash");
        var second = provider.assess(snapshot(), "stable-hash");
        assertEquals(first.choices(), second.choices());
        assertEquals(first.latencyMs(), second.latencyMs());
        for (var choice : first.choices()) {
            assertTrue(choice.confidence() >= 0.90 && choice.confidence() <= 0.99,
                    "seeded confidence stays in [0.90, 0.99]");
            assertEquals(choice.confidence(), choice.probabilities().get(choice.choice()));
        }
    }

    @Test void mockNeverMetersUsage() throws Exception {
        assertNull(provider().assess(snapshot(), "hash-1").usage());
    }

    @Test void latencyStaysBounded() throws Exception {
        for (String hash : List.of("a", "b", "c")) {
            long latency = provider().assess(snapshot(), hash).latencyMs();
            assertTrue(latency >= 5 && latency <= 25, "latency " + latency + "ms within 5..25");
        }
    }

    @Test void unavailableFaultThrowsBeforeAnswering() {
        var provider = provider();
        provider.setFaultForTest("UNAVAILABLE");
        assertThrows(RiskSignalProvider.ProviderUnavailable.class,
                () -> provider.assess(snapshot(), "hash-1"));
    }

    @Test void invalidFaultReturnsAnUnvalidatableResponse() {
        var provider = provider();
        provider.setFaultForTest("INVALID");
        assertThrows(RiskSignalProvider.InvalidResponse.class,
                () -> provider.assess(snapshot(), "hash-1"));
    }

    @Test void unknownFaultAbstainsOnQ02Honestly() throws Exception {
        var provider = provider();
        provider.setFaultForTest("UNKNOWN");
        var assessment = provider.assess(snapshot(), "hash-1");
        assertEquals(List.of("Q02"), assessment.noulQuestionIds());
        assertEquals(1, assessment.choices().size());
        assertDoesNotThrow(() -> RiskSignalProvider.validate(assessment, "mock-2026-10"));
    }

    @Test void timeoutFaultSleepsPastTheDeadline() {
        var fast = new MockRiskSignalProvider(new RiskProperties("RULES_PLUS_MOCK", "1.0",
                "mock-2026-10", "0.4", 50, 60, "TIMEOUT", 1.0));
        assertThrows(RiskSignalProvider.ProviderTimeout.class,
                () -> fast.assess(snapshot(), "hash-1"));
    }

    @Test void questionVersionMismatchIsInvalid() {
        var assessment = providerOkWithVersion("mock-1999-01");
        assertThrows(RiskSignalProvider.InvalidResponse.class,
                () -> RiskSignalProvider.validate(assessment, "mock-2026-10"));
    }

    @Test void callCounterProvesBoundedCalls() throws Exception {
        var provider = provider();
        provider.resetCallsForTest();
        provider.assess(snapshot(), "hash-1");
        provider.assess(snapshot(), "hash-1");
        assertEquals(2, provider.callsForTest());
    }

    private static String answer(RiskSignalProvider.Assessment assessment, String questionId) {
        return assessment.choices().stream().filter(c -> c.questionId().equals(questionId))
                .findFirst().orElseThrow().choice();
    }

    private RiskSignalProvider.Assessment providerOkWithVersion(String questionVersion) {
        return new RiskSignalProvider.Assessment(UUID.randomUUID(), "hash",
                MockRiskSignalProvider.PROVIDER_VERSION, MockRiskSignalProvider.MODEL_VERSION,
                questionVersion,
                List.of(new RiskSignalProvider.ChoiceSignal("Q01", "true", Map.of("true", 0.9, "false", 0.1), 0.9),
                        new RiskSignalProvider.ChoiceSignal("Q02", "false", Map.of("true", 0.1, "false", 0.9), 0.9)),
                List.of(), 5, "OK", null);
    }
}
