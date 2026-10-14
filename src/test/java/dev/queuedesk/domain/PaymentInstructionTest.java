package dev.queuedesk.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaymentInstructionTest {
    @Test void validatesWithoutSpring() {
        var good = new PaymentInstruction(" ref ", "run", "SENDER_A", "RECEIVER_B", 12500L, "USD");
        assertEquals("ref", good.clientReference());
        assertDoesNotThrow(() -> new PaymentInstruction("ref", "run", "SENDER_A", "RECEIVER_B", 1L, "USD"));
        assertDoesNotThrow(() -> new PaymentInstruction("ref", "run", "SENDER_A", "RECEIVER_B", 10_000_000L, "USD"));
        for (Long amount : new Long[]{null, 0L, -1L, 10_000_001L})
            assertThrows(IllegalArgumentException.class, () -> new PaymentInstruction("ref", "run", "SENDER_A", "RECEIVER_B", amount, "USD"));
        assertThrows(IllegalArgumentException.class, () -> new PaymentInstruction("ref", "run", "REAL_BANK", "RECEIVER_B", 1L, "USD"));
        assertThrows(IllegalArgumentException.class, () -> new PaymentInstruction("ref", "run", "SENDER_A", "RECEIVER_B", 1L, "EUR"));
        assertThrows(IllegalArgumentException.class, () -> PaymentInstruction.token("", "key"));
    }
}
