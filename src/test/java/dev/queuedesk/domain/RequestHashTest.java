package dev.queuedesk.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The request fingerprint: deterministic for equal payloads, sensitive to
 * every business field, and version-mixed so a format bump changes every
 * digest instead of colliding with stored ones.
 */
class RequestHashTest {
    private PaymentInstruction payload(long amount) {
        return new PaymentInstruction("ref", "run", "SENDER_A", "RECEIVER_B", amount, "USD");
    }
    @Test void deterministicAndSensitiveToBusinessFields() {
        assertEquals(RequestHash.of(payload(5)), RequestHash.of(payload(5)));
        assertEquals(64, RequestHash.of(payload(5)).length());
        assertNotEquals(RequestHash.of(payload(5)), RequestHash.of(payload(6)));
        assertEquals("REQUEST_HASH_V1", RequestHash.VERSION);
    }
    @Test void versionIsMixedIntoTheDigest() throws Exception {
        // The digest must differ from the naive unversioned join: otherwise a
        // VERSION bump would silently keep every historical digest.
        var naive = String.join("|", "ref", "run", "SENDER_A", "RECEIVER_B", "5", "USD");
        byte[] raw = java.security.MessageDigest.getInstance("SHA-256")
                .digest(naive.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var hex = new StringBuilder();
        for (byte b : raw) hex.append(String.format("%02x", b));
        assertNotEquals(hex.toString(), RequestHash.of(payload(5)));
    }
}
