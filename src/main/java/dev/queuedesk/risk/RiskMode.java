package dev.queuedesk.risk;

// Risk gate modes (RISK-02, PRD §23-24). OFF keeps the base application
// working untouched, RULES_ONLY is the no-provider baseline, RULES_PLUS_MOCK
// consults the deterministic mock provider, and RULES_PLUS_JEV calls the live
// TypeSafe endpoint under the $1-cap ledger (human approval required to run).
// Modes never silently degrade: unknown input fails fast with the set named.
public enum RiskMode {
    OFF,
    RULES_ONLY,
    RULES_PLUS_MOCK,
    RULES_PLUS_JEV;

    public static RiskMode parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("queuedesk.risk.mode is required (OFF|RULES_ONLY|RULES_PLUS_MOCK|RULES_PLUS_JEV)");
        String name = raw.trim().toUpperCase(java.util.Locale.ROOT);
        try {
            return RiskMode.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("queuedesk.risk.mode must be OFF, RULES_ONLY, RULES_PLUS_MOCK, or RULES_PLUS_JEV (got '" + raw + "')");
        }
    }
}
