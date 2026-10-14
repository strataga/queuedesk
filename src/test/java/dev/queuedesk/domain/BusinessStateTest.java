package dev.queuedesk.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The business state machine: every legal transition, the terminal states
 * that must never regress, and proof that delivery detail
 * ({@code PENDING_PUBLISH} and friends) is not a business state anywhere.
 */
class BusinessStateTest {
    @Test void legalTransitions() {
        assertTrue(BusinessState.allowed("RECEIVED", "BUSINESS_ACCEPTED"));
        assertTrue(BusinessState.allowed("RECEIVED", "REJECTED"));
        assertTrue(BusinessState.allowed("BUSINESS_ACCEPTED", "COMPLETED_SIMULATED"));
        assertTrue(BusinessState.allowed("BUSINESS_ACCEPTED", "REJECTED"));
        assertTrue(BusinessState.allowed("BUSINESS_ACCEPTED", "PENDING_CONFIRMATION"));
        assertTrue(BusinessState.allowed("PENDING_CONFIRMATION", "COMPLETED_SIMULATED"));
        assertTrue(BusinessState.allowed("PENDING_CONFIRMATION", "REJECTED"));
    }
    @Test void terminalStatesNeverRegress() {
        assertFalse(BusinessState.allowed("COMPLETED_SIMULATED", "RECEIVED"));
        assertFalse(BusinessState.allowed("COMPLETED_SIMULATED", "BUSINESS_ACCEPTED"));
        assertFalse(BusinessState.allowed("REJECTED", "RECEIVED"));
        assertFalse(BusinessState.allowed("REJECTED", "COMPLETED_SIMULATED"));
        assertFalse(BusinessState.allowed("BUSINESS_ACCEPTED", "RECEIVED"));
        assertFalse(BusinessState.allowed("RECEIVED", "COMPLETED_SIMULATED"));
        assertFalse(BusinessState.allowed("RECEIVED", "PENDING_CONFIRMATION"));
    }
    @Test void terminalStates() {
        assertTrue(BusinessState.isTerminal("COMPLETED_SIMULATED"));
        assertTrue(BusinessState.isTerminal("REJECTED"));
        assertFalse(BusinessState.isTerminal("RECEIVED"));
        assertFalse(BusinessState.isTerminal("BUSINESS_ACCEPTED"));
        assertFalse(BusinessState.isTerminal("PENDING_CONFIRMATION"));
    }
    @Test void riskHeldTransitions() {
        assertTrue(BusinessState.allowed("RECEIVED", "RISK_HELD"));
        assertTrue(BusinessState.allowed("RISK_HELD", "RECEIVED"));
        assertTrue(BusinessState.allowed("RISK_HELD", "REJECTED"));
        assertFalse(BusinessState.allowed("RISK_HELD", "BUSINESS_ACCEPTED"));
        assertFalse(BusinessState.allowed("RISK_HELD", "COMPLETED_SIMULATED"));
        assertFalse(BusinessState.allowed("BUSINESS_ACCEPTED", "RISK_HELD"));
        assertFalse(BusinessState.isTerminal("RISK_HELD"));
    }
    @Test void deliveryDetailIsNotABusinessState() {
        assertFalse(BusinessState.allowed("RECEIVED", "PENDING_PUBLISH"));
        assertFalse(BusinessState.allowed("RECEIVED", "PUBLISHED"));
        assertFalse(BusinessState.allowed("PUBLISHED", "COMPLETED_SIMULATED"));
        assertFalse(BusinessState.allowed("UNKNOWN", "RECEIVED"));
    }
}
