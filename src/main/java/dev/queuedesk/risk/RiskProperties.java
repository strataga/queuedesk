package dev.queuedesk.risk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Risk gate configuration, validated eagerly at boot.
 *
 * <p>Constructor parsing fails fast: an unknown mode, a malformed version
 * token, or an out-of-range deadline, window, or budget stops the process
 * with a named error instead of running under a misunderstood gate. The
 * three version strings (features, questions, policy) are part of every
 * assessment's identity, so they are validated as tokens, not free text.
 */
@Component
public class RiskProperties {
    private final RiskMode mode;
    private final String featureVersion;
    private final String questionVersion;
    private final String policyVersion;
    private final long providerDeadlineMs;
    private final int velocityWindowMinutes;
    private final String mockFault;
    private final double jevBudgetUsd;

    public RiskProperties(
            @Value("${queuedesk.risk.mode:OFF}") String mode,
            @Value("${queuedesk.risk.feature-version:1.0}") String featureVersion,
            @Value("${queuedesk.risk.question-version:mock-2026-10}") String questionVersion,
            @Value("${queuedesk.risk.policy-version:0.4}") String policyVersion,
            @Value("${queuedesk.risk.provider-deadline-ms:2000}") long providerDeadlineMs,
            @Value("${queuedesk.risk.velocity-window-minutes:60}") int velocityWindowMinutes,
            @Value("${queuedesk.risk.mock-fault:NONE}") String mockFault,
            @Value("${queuedesk.risk.jev.budget-usd:1.0}") double jevBudgetUsd) {
        this.mode = RiskMode.parse(mode);
        this.featureVersion = requireToken(featureVersion, "queuedesk.risk.feature-version");
        this.questionVersion = requireToken(questionVersion, "queuedesk.risk.question-version");
        this.policyVersion = requireToken(policyVersion, "queuedesk.risk.policy-version");
        if (providerDeadlineMs < 50 || providerDeadlineMs > 30_000)
            throw new IllegalArgumentException("queuedesk.risk.provider-deadline-ms must be 50..30000");
        this.providerDeadlineMs = providerDeadlineMs;
        if (velocityWindowMinutes < 1 || velocityWindowMinutes > 1440)
            throw new IllegalArgumentException("queuedesk.risk.velocity-window-minutes must be 1..1440");
        this.velocityWindowMinutes = velocityWindowMinutes;
        this.mockFault = MockRiskSignalProvider.requireFault(mockFault);
        if (!(jevBudgetUsd >= 0.01) || jevBudgetUsd > 10_000)
            throw new IllegalArgumentException("queuedesk.risk.jev.budget-usd must be 0.01..10000");
        this.jevBudgetUsd = jevBudgetUsd;
    }

    /**
     * Plain-Java factory for unit tests, with production defaults. A second
     * constructor would confuse Spring's single-constructor injection, so
     * tests use this instead.
     */
    public static RiskProperties forTest(RiskMode mode) {
        return new RiskProperties(mode.name(), "1.0", "mock-2026-10", "0.4", 2000, 60, "NONE", 1.0);
    }

    private static String requireToken(String value, String field) {
        if (value == null || !value.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,39}"))
            throw new IllegalArgumentException(field + " must be a 1..40 character version token");
        return value.trim();
    }

    /** The configured gate mode. */
    public RiskMode mode() { return mode; }
    /** Feature-schema version stamped on snapshots and assessments. */
    public String featureVersion() { return featureVersion; }
    /** Question-set version expected from providers. */
    public String questionVersion() { return questionVersion; }
    /** Policy version stamped on assessments. */
    public String policyVersion() { return policyVersion; }
    /** Hard deadline for one provider call, in milliseconds. */
    public long providerDeadlineMs() { return providerDeadlineMs; }
    /** How far back sender history counts toward velocity features, in minutes. */
    public int velocityWindowMinutes() { return velocityWindowMinutes; }
    /** The mock provider's configured fault injection (NONE in normal runs). */
    public String mockFault() { return mockFault; }
    /** The live-provider budget cap in USD. */
    public double jevBudgetUsd() { return jevBudgetUsd; }
    /** The budget cap in nano-dollars, for exact integer ledger comparisons. */
    public long jevBudgetNanoUsd() { return (long) (jevBudgetUsd * 1_000_000_000L); }
}
