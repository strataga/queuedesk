package dev.queuedesk.domain;

import java.util.Map;
import java.util.Set;

/**
 * The business state machine for one payment instruction (PRD §6, STATE-01).
 *
 * <p>Only the transitions listed in {@link #ALLOWED} are legal, and a terminal
 * result ({@link #COMPLETED_SIMULATED} or {@link #REJECTED}) never regresses.
 * Delivery detail such as {@code PENDING_PUBLISH} or {@code PUBLISHED} lives
 * in a separate dimension (the outbox) and never appears here, so a broker
 * outage can delay a payment without changing what the business decided.
 *
 * <p>States are plain strings because they are stored as {@code TEXT} in
 * Postgres and surfaced verbatim in the API and dashboard.
 */
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

    /**
     * Returns {@code true} when moving from {@code from} to {@code to} is a
     * legal transition. Unknown source states allow nothing.
     */
    public static boolean allowed(String from, String to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** Returns {@code true} when {@code state} is final and must never change again. */
    public static boolean isTerminal(String state) { return TERMINAL.contains(state); }
}
