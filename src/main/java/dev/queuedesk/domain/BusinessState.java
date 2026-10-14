package dev.queuedesk.domain;

import java.util.Map;
import java.util.Set;

// The business state machine (PRD §6, STATE-01): legal transitions only, no
// terminal result ever regresses. Delivery detail (PENDING_PUBLISH/PUBLISHED/…)
// lives in a separate dimension and never appears here.
public final class BusinessState {
    public static final String RECEIVED = "RECEIVED";
    public static final String BUSINESS_ACCEPTED = "BUSINESS_ACCEPTED";
    public static final String REJECTED = "REJECTED";
    public static final String COMPLETED_SIMULATED = "COMPLETED_SIMULATED";
    public static final String PENDING_CONFIRMATION = "PENDING_CONFIRMATION";
    public static final String RISK_HELD = "RISK_HELD";
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            RECEIVED, Set.of(BUSINESS_ACCEPTED, REJECTED, RISK_HELD),
            BUSINESS_ACCEPTED, Set.of(COMPLETED_SIMULATED, REJECTED, PENDING_CONFIRMATION),
            PENDING_CONFIRMATION, Set.of(COMPLETED_SIMULATED, REJECTED),
            RISK_HELD, Set.of(RECEIVED, REJECTED));
    private static final Set<String> TERMINAL = Set.of(COMPLETED_SIMULATED, REJECTED);
    private BusinessState() {}
    public static boolean allowed(String from, String to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }
    public static boolean isTerminal(String state) { return TERMINAL.contains(state); }
}
