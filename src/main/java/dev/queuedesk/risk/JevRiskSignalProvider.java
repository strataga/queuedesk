package dev.queuedesk.risk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Live Jev adapter: implements the same {@link RiskSignalProvider} contract
 * as the mock, so policy and gate code never change when the provider does.
 * Wired as the primary provider bean only in {@code RULES_PLUS_JEV} mode
 * (see {@link RiskLiveConfig}); tests construct it directly with a stubbed
 * transport or the real HTTP transport against a loopback server. See
 * RUNBOOK-RISK for the live-run procedure.
 *
 * <p>Wire shape follows the TypeSafe API: {@code POST {base}/v1/systemone}
 * with a bearer credential, {@code {state, model, questions}} in and
 * {@code {model, answers, usage}} out. Both lab questions go as choice
 * questions with true/false criteria under the shared Q01/Q02 IDs, so the
 * shared {@code validate()} applies unchanged. Question wording is fixed
 * code, versioned by {@link #QUESTION_VERSION}: operators cannot silently
 * change what is asked; new wording is a code change plus re-evaluation.
 * The state carries snapshot numbers only. No actor identities exist on the
 * snapshot, and none are added here.
 *
 * <p>Before any call, the durable budget ledger is checked: spend is the
 * sum over stored assessments, so restarts never reset the cap. A breached
 * cap refuses as unavailable and the gate holds, visibly.
 */
public class JevRiskSignalProvider implements RiskSignalProvider {
    /** Adapter version stamped on live assessments (distinct from the model version). */
    public static final String PROVIDER_VERSION = "jev-http-1.0";
    /** Fixed question-wording version for the live prompt text below. */
    public static final String QUESTION_VERSION = "jev-q1";
    static final String Q01_INSTRUCTIONS =
            "Is the recipient new to this sender? Answer from receiver_seen_before in the state.";
    static final String Q01_TRUE = "The sender has never paid this receiver before.";
    static final String Q01_FALSE = "The sender has paid this receiver before.";
    static final String Q02_INSTRUCTIONS =
            "Is the amount unusual for this sender? Answer from amount_to_baseline_ratio in the state.";
    static final String Q02_TRUE = "The amount is far from this sender's baseline.";
    static final String Q02_FALSE = "The amount is near this sender's baseline.";

    private final JevProperties properties;
    private final JevTransport transport;
    private final ObjectMapper mapper;
    private final RiskAssessmentRepository assessments;
    private final long budgetNanoUsd;

    public JevRiskSignalProvider(JevProperties properties, JevTransport transport, ObjectMapper mapper,
            RiskAssessmentRepository assessments, long budgetNanoUsd) {
        this.properties = properties;
        this.transport = transport;
        this.mapper = mapper;
        this.assessments = assessments;
        this.budgetNanoUsd = budgetNanoUsd;
    }

    @Override
    public Assessment assess(FeatureSnapshot snapshot, String inputHash)
            throws RiskSignalProvider.ProviderException {
        // $1-cap ledger (PRD §25): durable spend is the SUM over stored
        // assessments, so restarts never reset the budget. A breached cap
        // refuses as unavailable — the gate holds NOT_ASSESSED, fail-safe and
        // visible. Concurrent in-flight calls can overshoot by one call each
        // (~$0.00002 at list price); the lab accepts that bound.
        var spent = assessments.usageTotals();
        long spentNano = JevProperties.costNanoUsd(spent.inputTokens());
        if (spentNano >= budgetNanoUsd)
            throw new RiskSignalProvider.ProviderUnavailable(
                    "jev budget exhausted: spent $" + nanoToDollars(spentNano)
                            + " of $" + nanoToDollars(budgetNanoUsd));
        String requestJson;
        try {
            requestJson = mapper.writeValueAsString(request(snapshot));
        } catch (Exception unserializable) {
            throw new RiskSignalProvider.InvalidResponse(
                    "jev request is not JSON-serializable: " + unserializable.getMessage());
        }
        long started = System.nanoTime();
        String body = transport.evaluate(requestJson);
        long latencyMs = (System.nanoTime() - started) / 1_000_000L;
        JsonNode root = parse(body);
        String modelVersion = text(root, "model");
        if (modelVersion == null)
            throw new RiskSignalProvider.InvalidResponse("jev response has no model version");
        if (!properties.model().equals(modelVersion))
            throw new RiskSignalProvider.InvalidResponse(
                    "jev wrong model: pinned " + properties.model() + ", got " + modelVersion);
        JsonNode answers = root.get("answers");
        if (answers == null || !answers.isObject())
            throw new RiskSignalProvider.InvalidResponse("jev response has no answers object");
        List<ChoiceSignal> choices = new ArrayList<>();
        for (String questionId : REQUIRED_QUESTIONS) {
            JsonNode answer = answers.get(questionId);
            if (answer == null || !answer.isObject())
                throw new RiskSignalProvider.InvalidResponse("jev response is missing answer " + questionId);
            choices.add(choice(questionId, answer));
        }
        Usage usage = usage(root);
        Assessment assessment = new Assessment(UUID.randomUUID(), inputHash, PROVIDER_VERSION, modelVersion,
                QUESTION_VERSION, List.copyOf(choices), List.of(), latencyMs, "OK", usage);
        RiskSignalProvider.validate(assessment, QUESTION_VERSION);
        return assessment;
    }

    // Usage is required on success: the API always reports it, and silent
    // unmetered calls would break the $1-cap ledger. Absence is a contract
    // violation, not a zero.
    private static Usage usage(JsonNode root) throws RiskSignalProvider.InvalidResponse {
        JsonNode usage = root.get("usage");
        if (usage == null || !usage.isObject())
            throw new RiskSignalProvider.InvalidResponse("jev response has no usage object");
        JsonNode input = usage.get("input_tokens");
        JsonNode output = usage.get("output_tokens");
        if (input == null || !input.isNumber() || output == null || !output.isNumber())
            throw new RiskSignalProvider.InvalidResponse("jev usage must carry numeric token counts");
        try {
            return new Usage(input.longValue(), output.longValue());
        } catch (IllegalArgumentException negative) {
            throw new RiskSignalProvider.InvalidResponse("jev usage token counts must be >= 0");
        }
    }

    private static String nanoToDollars(long nano) {
        return String.format(java.util.Locale.ROOT, "%.6f", nano / 1_000_000_000.0);
    }

    private Map<String, Object> request(FeatureSnapshot snapshot) {
        ObjectNode state = mapper.createObjectNode();
        state.put("amount_minor", snapshot.amountMinor());
        state.put("prior_unique_count", snapshot.priorUniqueCount());
        if (snapshot.missingElapsed()) state.putNull("elapsed_since_previous_ms");
        else state.put("elapsed_since_previous_ms", snapshot.elapsedSincePreviousMs());
        state.put("receiver_seen_before", snapshot.receiverSeenBefore());
        if (snapshot.missingBaseline()) state.putNull("amount_to_baseline_ratio");
        else state.put("amount_to_baseline_ratio", snapshot.amountToBaselineRatio());
        state.put("missing_elapsed", snapshot.missingElapsed());
        state.put("missing_baseline", snapshot.missingBaseline());
        state.put("feature_version", snapshot.featureVersion());
        state.put("as_of", snapshot.asOf().toString());
        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put(Q01_NEW_RECIPIENT, choiceQuestion(Q01_INSTRUCTIONS, Q01_TRUE, Q01_FALSE));
        questions.put(Q02_UNUSUAL_AMOUNT, choiceQuestion(Q02_INSTRUCTIONS, Q02_TRUE, Q02_FALSE));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("state", state);
        request.put("model", properties.model());
        request.put("questions", questions);
        return request;
    }

    private static Map<String, Object> choiceQuestion(String instructions, String yes, String no) {
        return Map.of("type", "choice", "instructions", instructions,
                "criteria", Map.of("true", yes, "false", no));
    }

    private JsonNode parse(String body) throws RiskSignalProvider.InvalidResponse {
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject())
                throw new RiskSignalProvider.InvalidResponse("jev response is not a JSON object");
            return root;
        } catch (RiskSignalProvider.InvalidResponse invalid) {
            throw invalid;
        } catch (Exception unparsable) {
            throw new RiskSignalProvider.InvalidResponse(
                    "jev response is not JSON: " + unparsable.getMessage());
        }
    }

    private static ChoiceSignal choice(String questionId, JsonNode answer)
            throws RiskSignalProvider.InvalidResponse {
        if (!"choice".equals(text(answer, "type")))
            throw new RiskSignalProvider.InvalidResponse(questionId + ": expected a choice answer");
        String choice = text(answer, "choice");
        if (!"true".equals(choice) && !"false".equals(choice))
            throw new RiskSignalProvider.InvalidResponse(questionId + ": choice must be true|false");
        JsonNode probabilities = answer.get("probabilities");
        if (probabilities == null || !probabilities.isObject())
            throw new RiskSignalProvider.InvalidResponse(questionId + ": missing probabilities");
        Map<String, Double> distribution = new LinkedHashMap<>();
        for (String key : new String[] {"true", "false"}) {
            JsonNode value = probabilities.get(key);
            if (value == null || !value.isNumber())
                throw new RiskSignalProvider.InvalidResponse(
                        questionId + ": probabilities must carry numeric true|false");
            distribution.put(key, value.doubleValue());
        }
        JsonNode confidence = answer.get("confidence");
        if (confidence == null || !confidence.isNumber())
            throw new RiskSignalProvider.InvalidResponse(questionId + ": missing numeric confidence");
        return new ChoiceSignal(questionId, choice, Map.copyOf(distribution), confidence.doubleValue());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }
}
