package dev.queuedesk.risk;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Mode parsing without Spring: the four modes parse, and unknown input
 * fails fast with the supported set named. Bad gate configuration is
 * rejected at construction, never at first payment.
 */
class RiskModeTest {
    @Test void parsesTheFourModes() {
        assertEquals(RiskMode.OFF, RiskMode.parse("OFF"));
        assertEquals(RiskMode.RULES_ONLY, RiskMode.parse("RULES_ONLY"));
        assertEquals(RiskMode.RULES_PLUS_MOCK, RiskMode.parse("RULES_PLUS_MOCK"));
        assertEquals(RiskMode.RULES_PLUS_JEV, RiskMode.parse("RULES_PLUS_JEV"));
        assertEquals(RiskMode.OFF, RiskMode.parse("  off "));
    }

    @Test void unknownModesFailLoudly() {
        var error = assertThrows(IllegalArgumentException.class, () -> RiskMode.parse("AUTO"));
        assertTrue(error.getMessage().contains("RULES_PLUS_JEV"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> RiskMode.parse(""));
        assertThrows(IllegalArgumentException.class, () -> RiskMode.parse(null));
    }

    @Test void propertiesRejectBadConfigFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new RiskProperties("OFF", "1.0", "mock-2026-10", "0.4", 10, 60, "NONE", 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskProperties("OFF", "1.0", "mock-2026-10", "0.4", 2000, 0, "NONE", 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskProperties("OFF", "1.0", "mock-2026-10", "0.4", 2000, 60, "CHAOS", 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskProperties("OFF", "1.0", "mock-2026-10", "0.4", 2000, 60, "NONE", 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskProperties("OFF", "1.0", "mock-2026-10", "0.4", 2000, 60, "NONE", 10_001));
        assertEquals(RiskMode.RULES_PLUS_MOCK, RiskProperties.forTest(RiskMode.RULES_PLUS_MOCK).mode());
        assertEquals(1_000_000_000L, RiskProperties.forTest(RiskMode.OFF).jevBudgetNanoUsd());
    }
}
