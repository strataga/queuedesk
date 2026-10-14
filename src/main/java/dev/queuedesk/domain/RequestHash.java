package dev.queuedesk.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

// Semantic fingerprint v1: a hash of the normalized business payload only.
// Correlation metadata is excluded by construction: same key + same hash is a
// repeat, same key + different hash is a conflict. Plain Java, no Spring.
public final class RequestHash {
    public static final String VERSION = "REQUEST_HASH_V1";
    private RequestHash() {}
    public static String of(PaymentInstruction payload) {
        // VERSION leads the canonical string so a future bump changes every
        // digest. Fields are backslash-escaped: the pinned token charset
        // cannot contain '|' today, but the join must not depend on that.
        String canonical = String.join("|", VERSION, escape(payload.clientReference()), escape(payload.runId()),
                escape(payload.senderParticipant()), escape(payload.receiverParticipant()),
                payload.amountMinor().toString(), escape(payload.currency()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
    private static String escape(String field) {
        return field.replace("\\", "\\\\").replace("|", "\\|");
    }
}
