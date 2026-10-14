package dev.queuedesk.domain;

/**
 * One synthetic payment instruction, exactly as the client described it.
 *
 * <p>A record is immutable data with value equality (roughly a readonly
 * TypeScript object). This type deliberately knows nothing about Spring,
 * HTTP, or persistence: the compact constructor is the single place where
 * business validity is decided, so an invalid instruction cannot exist
 * anywhere in the system.
 *
 * <p>The lab pins the world down on purpose: sender {@code SENDER_A},
 * receiver {@code RECEIVER_B}, currency {@code USD}, and amounts from 1 to
 * 10,000,000 minor units (cents). Anything else is rejected here and
 * surfaces as HTTP 400 at the front door.
 *
 * @param clientReference caller-chosen reference, unique per real payment intent
 * @param runId groups instructions from one generator run or experiment
 * @param amountMinor amount in USD cents (minor units), never a float
 */
public record PaymentInstruction(String clientReference, String runId,
        String senderParticipant, String receiverParticipant, Long amountMinor, String currency) {
    public PaymentInstruction {
        clientReference = token(clientReference, "clientReference");
        runId = token(runId, "runId");
        if (!"SENDER_A".equals(senderParticipant) || !"RECEIVER_B".equals(receiverParticipant))
            throw new IllegalArgumentException("Participants must be SENDER_A and RECEIVER_B");
        if (amountMinor == null || amountMinor < 1 || amountMinor > 10_000_000L)
            throw new IllegalArgumentException("amountMinor must be 1..10000000 (synthetic lab cap)");
        if (!"USD".equals(currency)) throw new IllegalArgumentException("currency must be USD");
    }

    /**
     * Validates and trims a synthetic token field such as a reference or key.
     * Tokens are 1–80 characters of letters, digits, {@code _}, or {@code -},
     * which keeps them safe as idempotency keys, URL parts, and log fields.
     *
     * @param value the raw caller-supplied value
     * @param field the field name, used in the error message
     * @return the trimmed token
     * @throws IllegalArgumentException when the value is missing or off-charset
     */
    public static String token(String value, String field) {
        if (value == null || !value.trim().matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException(field + " must be a 1..80 character synthetic token");
        return value.trim();
    }
}
