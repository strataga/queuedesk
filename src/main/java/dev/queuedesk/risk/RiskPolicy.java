package dev.queuedesk.risk;

import java.util.ArrayList;
import java.util.List;

// Policy composition (RISK-03, PRD §24): pure Java, owns ALLOW / REVIEW /
// NOT_ASSESSED / REJECT. Deterministic rules evaluate first and any match
// holds for review; provider signals are context only. REJECT fires solely
// under the explicit deterministic simulation block below — never from a
// score or confidence alone. Reason codes are predefined constants, never
// model prose; confidence labels the exact question and the UI must never
// render "probability of fraud".
public final class RiskPolicy {
    public static final String RULES_VERSION = "0.4";
    public static final String NEW_RECIPIENT = "NEW_RECIPIENT";
    public static final String UNUSUAL_AMOUNT = "UNUSUAL_AMOUNT";
    public static final String SIMULATED_POLICY_BLOCK = "SIMULATED_POLICY_BLOCK";
    public static final List<String> KNOWN_CODES = List.of(NEW_RECIPIENT, UNUSUAL_AMOUNT, SIMULATED_POLICY_BLOCK);

    // An amount at least 5x the sender's window median is unusual. Missing
    // baseline (first-in-window) is not unusual by itself — NEW_RECIPIENT
    // already covers that case.
    public static final double UNUSUAL_RATIO = 5.0;
    // Explicit deterministic simulation block (the ONLY reject path): a
    // near-cap amount to a never-seen receiver. Simulation-only; a live policy
    // would replace this predicate, never a threshold tweak.
    public static final long SIMULATED_BLOCK_AMOUNT_MINOR = 9_500_000L;

    public record Decision(String policyResult, List<String> reasonCodes, String evaluationStatus) {}

    private RiskPolicy() {}

    public static boolean unusualAmount(FeatureSnapshot snapshot) {
        return !snapshot.missingBaseline() && snapshot.amountToBaselineRatio() >= UNUSUAL_RATIO;
    }

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

    // provider == null means RULES_ONLY (provider not consulted). Otherwise the
    // assessment arrived OK and its signals are context: matched rules still
    // drive the outcome; a required Noul (abstention) forces NOT_ASSESSED.
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

    // Failure mapping: rules STILL evaluate first on a faulted call (they are
    // deterministic local facts — a provider outage must not flip a REVIEW to
    // something else, nor hide the match). Only genuinely unevaluated quiet
    // traffic holds NOT_ASSESSED, always with the fault's own evaluationStatus
    // (never an allow, never a silent retry).
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
