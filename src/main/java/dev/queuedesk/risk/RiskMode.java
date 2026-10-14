package dev.queuedesk.risk;

/**
 * Risk gate modes, from fully off to live provider calls.
 *
 * <p>{@code OFF} keeps the base payment flow untouched. {@code RULES_ONLY}
 * is the no-provider baseline. {@code RULES_PLUS_MOCK} adds the free,
 * deterministic mock provider. {@code RULES_PLUS_JEV} calls the live
 * TypeSafe endpoint under the durable budget ledger and requires a
 * credential. Modes never silently degrade into each other: an unknown
 * value fails fast at boot with the valid set named.
 */
public enum RiskMode {
    /** No risk check; payments flow straight to settlement. The lab default. */
    OFF,
    /** Deterministic rules only; no provider is consulted. */
    RULES_ONLY,
    /** Rules plus the deterministic mock provider (free, no network). */
    RULES_PLUS_MOCK,
    /** Rules plus the live Jev model, credential-gated and budget-capped. */
    RULES_PLUS_JEV;

    /**
     * Parses a configured mode, case-insensitively.
     *
     * @throws IllegalArgumentException naming the valid set when the value is missing or unknown
     */
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
