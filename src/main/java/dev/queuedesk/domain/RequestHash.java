package dev.queuedesk.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Semantic fingerprint of a payment payload, used to tell a safe retry from
 * an idempotency conflict.
 *
 * <p>The hash covers the normalized business payload only. Correlation
 * metadata is excluded by construction, so the rule at admission time is
 * simple: same key + same hash means "exact repeat, return the original";
 * same key + different hash means "conflict, reject with 409". Plain Java,
 * no Spring, so the generator and the tests can share the exact contract.
 */
public final class RequestHash {
    /** Canonical-form version. Bumping it deliberately changes every digest. */
    public static final String VERSION = "REQUEST_HASH_V1";
    private RequestHash() {}

    /**
     * Returns the lowercase hex SHA-256 of the payload's canonical string.
     * The payload is already normalized by {@link PaymentInstruction}'s
     * constructor (trimmed tokens, validated fields), so equal business
     * payloads always hash alike.
     */
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
