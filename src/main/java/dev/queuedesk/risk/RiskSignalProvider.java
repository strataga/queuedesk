package dev.queuedesk.risk;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The application contract every risk-signal provider must satisfy: typed
 * answers to fixed questions, pinned versions, measured latency, and an
 * honest evaluation status.
 *
 * <p>Java-first validation (exact question IDs and types, allowed keys,
 * finite ranges, distribution totals) runs on <em>every</em> response,
 * including the mock's. That is deliberate: the mock proves the same
 * adapter path the live provider must pass, so swapping providers never
 * changes what the gate accepts. Policy code depends only on this
 * interface, never on a provider's wire format.
 */
public interface RiskSignalProvider {

    /**
     * One typed answer: the chosen value, its full probability distribution,
     * and the confidence in the answer to the exact question asked. That
     * confidence is never a probability of fraud. A provider that cannot
     * answer records a Noul (abstention) instead; see {@link Assessment}.
     */
    record ChoiceSignal(String questionId, String choice, Map<String, Double> probabilities, double confidence) {}

    /**
     * Metered token usage, {@code null} when the provider reports none (the
     * mock never spends). Feeds the durable budget ledger, never the policy.
     */
    record Usage(long inputTokens, long outputTokens) {
        public Usage {
            if (inputTokens < 0 || outputTokens < 0)
                throw new IllegalArgumentException("usage token counts must be >= 0");
        }
    }
    /**
     * One completed provider evaluation: answers, abstentions
     * ({@code noulQuestionIds}), versions, latency, status, and usage. The
     * {@code inputHash} ties the answers to the exact snapshot assessed.
     */
    record Assessment(UUID assessmentId, String inputHash, String providerVersion, String modelVersion,
            String questionVersion, List<ChoiceSignal> choices, List<String> noulQuestionIds,
            long latencyMs, String evaluationStatus, Usage usage) {}

    /**
     * Assesses one frozen feature snapshot.
     *
     * @throws ProviderException (typed by failure kind) when no valid
     *         assessment could be produced; the gate maps each kind to a
     *         fail-closed hold
     */
    Assessment assess(FeatureSnapshot snapshot, String inputHash) throws ProviderException;

    /**
     * Base type for typed provider failures. Each subtype names the honest
     * {@code evaluationStatus} the gate records on its hold, instead of
     * collapsing every failure into a generic error. (A sealed class, not
     * records: records cannot extend {@link Throwable}.)
     */
    abstract sealed class ProviderException extends Exception
            permits ProviderTimeout, InvalidResponse, ProviderUnavailable {
        protected ProviderException(String detail) { super(detail); }
        public abstract String evaluationStatus();
        public String detail() { return getMessage(); }
    }
    /** The provider did not answer within the deadline. */
    final class ProviderTimeout extends ProviderException {
        public ProviderTimeout(String detail) { super(detail); }
        @Override public String evaluationStatus() { return "TIMEOUT"; }
    }
    /** The provider answered, but the response failed contract validation. */
    final class InvalidResponse extends ProviderException {
        public InvalidResponse(String detail) { super(detail); }
        @Override public String evaluationStatus() { return "INVALID_RESPONSE"; }
    }
    /** The provider could not be reached (or refused: auth, overload, budget). */
    final class ProviderUnavailable extends ProviderException {
        public ProviderUnavailable(String detail) { super(detail); }
        @Override public String evaluationStatus() { return "PROVIDER_UNAVAILABLE"; }
    }

    /** Stable ID: "Is the recipient new to this sender?" */
    String Q01_NEW_RECIPIENT = "Q01";
    /** Stable ID: "Is the amount unusual for this sender?" */
    String Q02_UNUSUAL_AMOUNT = "Q02";
    /** The required boolean question set every provider must answer or abstain on. */
    List<String> REQUIRED_QUESTIONS = List.of(Q01_NEW_RECIPIENT, Q02_UNUSUAL_AMOUNT);

    /** Returns the fixed display text for a question ID. Wording changes are a version bump, not config. */
    static String questionText(String questionId) {
        return switch (questionId) {
            case Q01_NEW_RECIPIENT -> "Is the recipient new to this sender?";
            case Q02_UNUSUAL_AMOUNT -> "Is the amount unusual for this sender?";
            default -> throw new IllegalArgumentException("unknown questionId: " + questionId);
        };
    }

    /**
     * Shared adapter validation, applied to every provider response: exact
     * question IDs, boolean choices, finite [0,1] probabilities summing to
     * 1.0 (±1e-6), finite [0,1] confidence, no unknown IDs, no duplicates.
     *
     * @throws InvalidResponse on any violation; never returns a half-validated assessment
     */
    static void validate(Assessment assessment, String expectedQuestionVersion) throws InvalidResponse {
        if (assessment == null) throw new InvalidResponse("null assessment");
        if (!expectedQuestionVersion.equals(assessment.questionVersion()))
            throw new InvalidResponse("questionVersion mismatch: " + assessment.questionVersion());
        if (assessment.inputHash() == null || assessment.inputHash().isBlank())
            throw new InvalidResponse("missing inputHash");
        var seen = new java.util.HashSet<String>();
        for (var choice : assessment.choices()) {
            if (!REQUIRED_QUESTIONS.contains(choice.questionId()))
                throw new InvalidResponse("unknown questionId: " + choice.questionId());
            if (!seen.add(choice.questionId()))
                throw new InvalidResponse("duplicate questionId: " + choice.questionId());
            if (!"true".equals(choice.choice()) && !"false".equals(choice.choice()))
                throw new InvalidResponse(choice.questionId() + ": choice must be true|false");
            if (choice.probabilities() == null || choice.probabilities().size() != 2
                    || !choice.probabilities().containsKey("true") || !choice.probabilities().containsKey("false"))
                throw new InvalidResponse(choice.questionId() + ": probabilities must carry exactly true|false keys");
            double total = 0;
            for (var p : choice.probabilities().values()) {
                if (p == null || !Double.isFinite(p) || p < 0 || p > 1)
                    throw new InvalidResponse(choice.questionId() + ": probabilities must be finite [0,1]");
                total += p;
            }
            if (Math.abs(total - 1.0) > 1e-6)
                throw new InvalidResponse(choice.questionId() + ": probabilities must total 1.0");
            if (!Double.isFinite(choice.confidence()) || choice.confidence() < 0 || choice.confidence() > 1)
                throw new InvalidResponse(choice.questionId() + ": confidence must be finite [0,1]");
            // Confidence stays independent of the picked probability: the live
            // model reports its own calibration (Q02 confidence 0.49 beside
            // P(false)=0.74 on the first live call), and the policy never
            // reads confidence — equality was a mock-era assumption the wire
            // falsified, not a contract.
        }
        for (String noul : assessment.noulQuestionIds()) {
            if (!REQUIRED_QUESTIONS.contains(noul)) throw new InvalidResponse("unknown noul questionId: " + noul);
            if (!seen.add(noul)) throw new InvalidResponse("duplicate questionId: " + noul);
        }
    }
}
