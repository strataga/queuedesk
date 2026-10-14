package dev.queuedesk.risk;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// RiskSignalProvider application contract (RISK-02, PRD §24): assessmentId,
// inputHash, versions, typed signals, latency, evaluationStatus. Java-first
// validation (exact question IDs/types, allowed keys, finite ranges,
// distribution totals) runs on EVERY response including the mock's — proving
// the adapter path a future PR13 swap must satisfy.
public interface RiskSignalProvider {

    // Typed answers only: a Choice carries its full distribution plus the
    // confidence in the answer to the EXACT question (never "probability of
    // fraud"); a Noul declines to answer one required question.
    record ChoiceSignal(String questionId, String choice, Map<String, Double> probabilities, double confidence) {}
    // Metered usage, null when the provider reports none (the mock never
    // spends). Token counts feed the durable $1-cap ledger, never policy.
    record Usage(long inputTokens, long outputTokens) {
        public Usage {
            if (inputTokens < 0 || outputTokens < 0)
                throw new IllegalArgumentException("usage token counts must be >= 0");
        }
    }
    record Assessment(UUID assessmentId, String inputHash, String providerVersion, String modelVersion,
            String questionVersion, List<ChoiceSignal> choices, List<String> noulQuestionIds,
            long latencyMs, String evaluationStatus, Usage usage) {}

    Assessment assess(FeatureSnapshot snapshot, String inputHash) throws ProviderException;

    // Provider failures are typed so the gate maps each to its honest
    // evaluationStatus (NOT_ASSESSED hold) instead of a generic error.
    // (A sealed class, not records: records cannot extend Throwable.)
    abstract sealed class ProviderException extends Exception
            permits ProviderTimeout, InvalidResponse, ProviderUnavailable {
        protected ProviderException(String detail) { super(detail); }
        public abstract String evaluationStatus();
        public String detail() { return getMessage(); }
    }
    final class ProviderTimeout extends ProviderException {
        public ProviderTimeout(String detail) { super(detail); }
        @Override public String evaluationStatus() { return "TIMEOUT"; }
    }
    final class InvalidResponse extends ProviderException {
        public InvalidResponse(String detail) { super(detail); }
        @Override public String evaluationStatus() { return "INVALID_RESPONSE"; }
    }
    final class ProviderUnavailable extends ProviderException {
        public ProviderUnavailable(String detail) { super(detail); }
        @Override public String evaluationStatus() { return "PROVIDER_UNAVAILABLE"; }
    }

    // Mock question catalog: stable IDs, boolean choices, required set.
    String Q01_NEW_RECIPIENT = "Q01";
    String Q02_UNUSUAL_AMOUNT = "Q02";
    List<String> REQUIRED_QUESTIONS = List.of(Q01_NEW_RECIPIENT, Q02_UNUSUAL_AMOUNT);

    static String questionText(String questionId) {
        return switch (questionId) {
            case Q01_NEW_RECIPIENT -> "Is the recipient new to this sender?";
            case Q02_UNUSUAL_AMOUNT -> "Is the amount unusual for this sender?";
            default -> throw new IllegalArgumentException("unknown questionId: " + questionId);
        };
    }

    // Shared adapter validation: exact question IDs, boolean choices, finite
    // [0,1] probabilities summing to 1.0 (±1e-6), finite [0,1] confidence,
    // no unknown IDs, no duplicates. Throws InvalidResponse, never returns
    // a half-validated assessment.
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
