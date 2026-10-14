package dev.queuedesk.risk;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Jev adapter behind a scripted transport: response mapping, the typed
 * fault taxonomy, budget refusal before any call, and request shape
 * (snapshot numbers only, no identities to leak). No network, no
 * credentials, no Spring.
 */
class JevRiskSignalProviderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JevProperties PROPS =
            new JevProperties("http://127.0.0.1:9", "jev-1.13.0", "test-key", 2000);

    private static final class StubTransport implements JevTransport {
        final Deque<Object> scripted = new ArrayDeque<>();
        final List<String> requests = new ArrayList<>();

        @Override
        public String evaluate(String requestJson) throws RiskSignalProvider.ProviderException {
            requests.add(requestJson);
            Object next = scripted.poll();
            if (next instanceof RiskSignalProvider.ProviderException failed) throw failed;
            return (String) next;
        }
    }

    private static JevRiskSignalProvider adapter(JevProperties props, JevTransport transport) {
        return adapter(props, transport, 1_000_000_000L);
    }

    private static JevRiskSignalProvider adapter(JevProperties props, JevTransport transport, long budgetNano) {
        return new JevRiskSignalProvider(props, transport, MAPPER,
                new InMemoryRiskAssessmentRepository(), budgetNano);
    }

    private static FeatureSnapshot snapshot() {
        return new FeatureSnapshot(12500, 3, 45_000L, false, 2.5,
                Instant.parse("2026-10-06T00:00:00Z"), "1.0", false, false);
    }

    private static String validBody() {
        return """
                {"model":"jev-1.13.0","answers":{
                 "Q01":{"type":"choice","choice":"true","confidence":0.85,
                  "probabilities":{"true":0.85,"false":0.15}},
                 "Q02":{"type":"choice","choice":"false","confidence":0.9,
                  "probabilities":{"true":0.1,"false":0.9}}},
                 "usage":{"input_tokens":392,"output_tokens":65}}""";
    }

    @Test void mapsAValidJevResponse() throws Exception {
        var transport = new StubTransport();
        transport.scripted.add(validBody());
        var adapter = adapter(PROPS, transport);
        var assessment = adapter.assess(snapshot(), "hash-1");
        assertNotNull(assessment.assessmentId());
        assertEquals("hash-1", assessment.inputHash());
        assertEquals("jev-http-1.0", assessment.providerVersion());
        assertEquals("jev-1.13.0", assessment.modelVersion());
        assertEquals("jev-q1", assessment.questionVersion());
        assertEquals("OK", assessment.evaluationStatus());
        assertTrue(assessment.latencyMs() >= 0);
        assertEquals(List.of(), assessment.noulQuestionIds());
        assertEquals(2, assessment.choices().size());
        assertEquals(new RiskSignalProvider.ChoiceSignal("Q01", "true",
                java.util.Map.of("true", 0.85, "false", 0.15), 0.85), assessment.choices().get(0));
        assertEquals(new RiskSignalProvider.ChoiceSignal("Q02", "false",
                java.util.Map.of("true", 0.1, "false", 0.9), 0.9), assessment.choices().get(1));
        assertEquals(new RiskSignalProvider.Usage(392, 65), assessment.usage());
        assertEquals(1, transport.requests.size());
    }

    @Test void liveConfidenceStaysIndependentOfThePickedProbability() throws Exception {
        // Captured live wire bytes (6 Oct 2026): the model reports confidence
        // 0.49 alongside P(false)=0.74 on Q02. The mock-era rule that the two
        // must be equal rejected the first live call; the policy never reads
        // confidence, so independence is valid.
        var transport = new StubTransport();
        transport.scripted.add("""
                {"model":"jev-1.13.0","answers":{
                 "Q01":{"type":"choice","choice":"true","confidence":1.0,
                  "probabilities":{"true":1.0,"false":0.0}},
                 "Q02":{"type":"choice","choice":"false","confidence":0.49,
                  "probabilities":{"true":0.26,"false":0.74}}},
                 "usage":{"input_tokens":511,"output_tokens":63}}""");
        var assessment = adapter(PROPS, transport).assess(snapshot(), "hash-live");
        assertEquals("OK", assessment.evaluationStatus());
        assertEquals("true", assessment.choices().get(0).choice());
        assertEquals("false", assessment.choices().get(1).choice());
        assertEquals(0.49, assessment.choices().get(1).confidence());
        assertEquals(new RiskSignalProvider.Usage(511, 63), assessment.usage());
    }

    @Test void missingUsageIsInvalid() {
        var transport = new StubTransport();
        transport.scripted.add(validBody().replace("\"usage\":{\"input_tokens\":392,\"output_tokens\":65}", "\"usage\":{}"));
        var failed = assertThrows(RiskSignalProvider.InvalidResponse.class,
                () -> adapter(PROPS, transport).assess(snapshot(), "hash-1"));
        assertTrue(failed.detail().contains("usage"));
    }

    @Test void exhaustedBudgetRefusesBeforeAnyCall() {
        var repo = new InMemoryRiskAssessmentRepository();
        repo.save(new RiskAssessmentRepository.StoredAssessment(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), "1.0", "jev-q1", "0.4", "spent", "{}", "OK", "ALLOW",
                java.util.List.of(), "{}", 12L, java.time.Instant.now(), 100_000_000L, 0L));
        var transport = new StubTransport();
        transport.scripted.add(validBody());
        var adapter = new JevRiskSignalProvider(PROPS, transport, MAPPER, repo, 1_000_000_000L);
        var failed = assertThrows(RiskSignalProvider.ProviderUnavailable.class,
                () -> adapter.assess(snapshot(), "hash-1"));
        assertTrue(failed.detail().contains("budget exhausted"), failed.detail());
        assertEquals(0, transport.requests.size());
    }

    @Test void sharedValidationRejectsABrokenDistribution() {
        var transport = new StubTransport();
        transport.scripted.add(validBody().replace("0.85,\"false\":0.15", "0.85,\"false\":0.5"));
        var adapter = adapter(PROPS, transport);
        assertThrows(RiskSignalProvider.InvalidResponse.class, () -> adapter.assess(snapshot(), "hash-1"));
    }

    @Test void missingAnswerIsInvalid() {
        var transport = new StubTransport();
        transport.scripted.add("""
                {"model":"jev-1.13.0","answers":{
                 "Q01":{"type":"choice","choice":"true","confidence":0.85,
                  "probabilities":{"true":0.85,"false":0.15}}}}""");
        var adapter = adapter(PROPS, transport);
        var failed = assertThrows(RiskSignalProvider.InvalidResponse.class,
                () -> adapter.assess(snapshot(), "hash-1"));
        assertTrue(failed.detail().contains("Q02"));
    }

    @Test void nonChoiceAnswerIsInvalid() {
        var transport = new StubTransport();
        transport.scripted.add(validBody().replace("\"Q01\":{\"type\":\"choice\"", "\"Q01\":{\"type\":\"noul\""));
        var adapter = adapter(PROPS, transport);
        assertThrows(RiskSignalProvider.InvalidResponse.class, () -> adapter.assess(snapshot(), "hash-1"));
    }

    @Test void wrongModelIsInvalid() {
        var transport = new StubTransport();
        transport.scripted.add(validBody().replace("jev-1.13.0", "jev-1.12.0"));
        var adapter = adapter(PROPS, transport);
        var failed = assertThrows(RiskSignalProvider.InvalidResponse.class,
                () -> adapter.assess(snapshot(), "hash-1"));
        assertTrue(failed.detail().contains("jev-1.12.0"));
    }

    @Test void unparseableBodiesAreInvalid() {
        for (String body : new String[] {"not json", "[1,2]", ""}) {
            var transport = new StubTransport();
            transport.scripted.add(body);
            var adapter = adapter(PROPS, transport);
            assertThrows(RiskSignalProvider.InvalidResponse.class,
                    () -> adapter.assess(snapshot(), "hash-1"), "body: " + body);
        }
    }

    @Test void transportFaultsPropagateTyped() {
        for (var fault : List.of(new RiskSignalProvider.ProviderTimeout("slow"),
                new RiskSignalProvider.ProviderUnavailable("down"),
                new RiskSignalProvider.InvalidResponse("bad"))) {
            var transport = new StubTransport();
            transport.scripted.add(fault);
            var adapter = adapter(PROPS, transport);
            var thrown = assertThrows(fault.getClass(), () -> adapter.assess(snapshot(), "hash-1"));
            assertEquals(fault.detail(), thrown.detail());
            assertEquals(1, transport.requests.size());
        }
    }

    @Test void requestCarriesPinQuestionsAndNumericsOnly() throws Exception {
        var transport = new StubTransport();
        transport.scripted.add(validBody());
        adapter(PROPS, transport).assess(snapshot(), "hash-1");
        JsonNode request = MAPPER.readTree(transport.requests.get(0));
        assertEquals("jev-1.13.0", request.get("model").asString());
        JsonNode questions = request.get("questions");
        var ids = new TreeSet<>(questions.propertyNames());
        assertEquals(Set.of("Q01", "Q02"), ids);
        assertEquals("choice", questions.get("Q01").get("type").asString());
        assertEquals(JevRiskSignalProvider.Q01_INSTRUCTIONS, questions.get("Q01").get("instructions").asString());
        assertEquals(JevRiskSignalProvider.Q02_INSTRUCTIONS, questions.get("Q02").get("instructions").asString());
        JsonNode state = request.get("state");
        var keys = new TreeSet<>(state.propertyNames());
        assertEquals(Set.of("amount_minor", "prior_unique_count", "elapsed_since_previous_ms",
                "receiver_seen_before", "amount_to_baseline_ratio", "missing_elapsed", "missing_baseline",
                "feature_version", "as_of"), keys);
        assertEquals(12500, state.get("amount_minor").asLong());
        assertFalse(state.get("receiver_seen_before").asBoolean());
    }

    @Test void missingFeaturesTravelAsNullsWithFlags() throws Exception {
        var transport = new StubTransport();
        transport.scripted.add(validBody());
        var sparse = new FeatureSnapshot(500, 0, null, false, FeatureSnapshot.NO_BASELINE,
                Instant.parse("2026-10-06T00:00:00Z"), "1.0", true, true);
        adapter(PROPS, transport).assess(sparse, "hash-2");
        JsonNode state = MAPPER.readTree(transport.requests.get(0)).get("state");
        assertTrue(state.get("elapsed_since_previous_ms").isNull());
        assertTrue(state.get("amount_to_baseline_ratio").isNull());
        assertTrue(state.get("missing_elapsed").asBoolean());
        assertTrue(state.get("missing_baseline").asBoolean());
    }

    @Test void adapterFindsNoIdentitiesToLeak() throws Exception {
        var transport = new StubTransport();
        transport.scripted.add(validBody());
        adapter(PROPS, transport).assess(snapshot(), "hash-1");
        String request = transport.requests.get(0);
        assertFalse(request.contains("test-key"));
        assertEquals(1, request.split("jev-1.13.0", -1).length - 1,
                "the pinned model appears exactly once (no alias, no echo)");
    }

}
