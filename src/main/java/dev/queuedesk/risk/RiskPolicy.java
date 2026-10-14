package dev.queuedesk.risk;

import java.util.ArrayList;
import java.util.List;

/**
 * Policy composition: the pure-Java function that turns features and
 * provider signals into {@code ALLOW}, {@code REVIEW}, {@code NOT_ASSESSED},
 * or {@code REJECT}.
 *
 * <p>Deterministic rules evaluate first, and any match holds the payment
 * for review. Provider signals are context only: they can explain a hold,
 * but no score or confidence value can allow, hold, or reject anything by
 * itself. The single {@code REJECT} path is the explicit simulation block
 * below. Reason codes are predefined constants, never model prose, and
 * provider confidence means "confidence in the answer to the exact
 * question". It must never be presented as a probability of fraud.
 */
public final class RiskPolicy {
    /** Rules version, stored on every assessment so old decisions stay interpretable. */
    public static final String RULES_VERSION = "0.4";
    /** Reason code: the sender has never paid this receiver (in the run's history window). */
    public static final String NEW_RECIPIENT = "NEW_RECIPIENT";
    /** Reason code: the amount is far above the sender's recent median. */
    public static final String UNUSUAL_AMOUNT = "UNUSUAL_AMOUNT";
    /** Reason code: the deterministic simulation block fired (the only reject path). */
    public static final String SIMULATED_POLICY_BLOCK = "SIMULATED_POLICY_BLOCK";
    /** Every reason code this policy can emit; API filters validate against this set. */
    public static final List<String> KNOWN_CODES = List.of(NEW_RECIPIENT, UNUSUAL_AMOUNT, SIMULATED_POLICY_BLOCK);

    /**
     * An amount at least 5× the sender's window median counts as unusual. A
     * missing baseline (first payment in the window) is not unusual by
     * itself; {@link #NEW_RECIPIENT} already covers that case.
     */
    public static final double UNUSUAL_RATIO = 5.0;
    /**
     * The simulation block threshold (the only reject path): a near-cap
     * amount to a never-seen receiver. Simulation-only by design; a live
     * policy would replace this predicate, never just tweak the number.
     */
    public static final long SIMULATED_BLOCK_AMOUNT_MINOR = 9_500_000L;

    /** A composed verdict: result, the predefined reason codes behind it, and the evaluation status. */
    public record Decision(String policyResult, List<String> reasonCodes, String evaluationStatus) {}

    private RiskPolicy() {}

    /** Returns {@code true} when a baseline exists and this amount meets the unusual ratio. */
    public static boolean unusualAmount(FeatureSnapshot snapshot) {
        return !snapshot.missingBaseline() && snapshot.amountToBaselineRatio() >= UNUSUAL_RATIO;
    }

    /** Returns the human-readable evidence line for one matched rule, for the assessment detail view. */
    public static String ruleDetail(String code, FeatureSnapshot snapshot) {
        return switch (code) {
            case NEW_RECIPIENT -> "recipient_seen_before = false";
            case UNUSUAL_AMOUNT -> "amount_to_baseline_ratio = " + snapshot.amountToBaselineRatio()
                    + " >= " + UNUSUAL_RATIO;
            case SIMULATED_POLICY_BLOCK -> "amount_minor = " + snapshot.amountMinor()
                    + " >= " + SIMULATED_BLOCK_AMOUNT_MINOR + " with new recipient (simulation policy)";
            default -> throw new IllegalArgumentException("unknown reason code: " + code);
        };
    }

    /**
     * Composes the verdict for a completed evaluation.
     *
     * @param provider the provider's assessment, or {@code null} in
     *        rules-only mode (provider not consulted). When present, its
     *        signals are context: matched rules still drive the outcome, and
     *        an abstention (Noul) on a required question forces
     *        {@code NOT_ASSESSED} rather than an allow
     */
    public static Decision evaluate(FeatureSnapshot snapshot, RiskSignalProvider.Assessment provider) {
        var matched = matchedRules(snapshot);
        String status = provider == null ? "NOT_APPLICABLE" : provider.evaluationStatus();
        if (simulatedBlock(snapshot)) {
            var codes = new ArrayList<>(matched);
            codes.add(SIMULATED_POLICY_BLOCK);
            return new Decision("REJECT", List.copyOf(codes), status);
        }
        if (!matched.isEmpty()) return new Decision("REVIEW", matched, status);
        if (provider == null) return new Decision("ALLOW", List.of(), "NOT_APPLICABLE");
        if (!provider.noulQuestionIds().isEmpty())
            return new Decision("NOT_ASSESSED", List.of(), "UNKNOWN");
        return new Decision("ALLOW", List.of(), provider.evaluationStatus());
    }

    /**
     * Failure mapping: rules still evaluate first on a faulted provider call,
     * because they are deterministic local facts. A provider outage must not
     * flip a {@code REVIEW} into something else or hide the match. Only
     * genuinely unevaluated quiet traffic holds as {@code NOT_ASSESSED},
     * always carrying the fault's own status. Never an allow, never a
     * silent retry.
     */
    public static Decision evaluateOnFault(FeatureSnapshot snapshot, String evaluationStatus) {
        var matched = matchedRules(snapshot);
        if (simulatedBlock(snapshot)) {
            var codes = new ArrayList<>(matched);
            codes.add(SIMULATED_POLICY_BLOCK);
            return new Decision("REJECT", List.copyOf(codes), evaluationStatus);
        }
        if (!matched.isEmpty()) return new Decision("REVIEW", matched, evaluationStatus);
        return notAssessed(evaluationStatus);
    }

    /** Builds a fail-closed hold for work that could not be evaluated, labeled with why. */
    public static Decision notAssessed(String evaluationStatus) {
        return new Decision("NOT_ASSESSED", List.of(), evaluationStatus);
    }

    private static List<String> matchedRules(FeatureSnapshot snapshot) {
        var matched = new ArrayList<String>();
        if (!snapshot.receiverSeenBefore()) matched.add(NEW_RECIPIENT);
        if (unusualAmount(snapshot)) matched.add(UNUSUAL_AMOUNT);
        return List.copyOf(matched);
    }

    private static boolean simulatedBlock(FeatureSnapshot snapshot) {
        return !snapshot.receiverSeenBefore() && snapshot.amountMinor() >= SIMULATED_BLOCK_AMOUNT_MINOR;
    }
}
