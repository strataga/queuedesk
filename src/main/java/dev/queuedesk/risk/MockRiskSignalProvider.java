package dev.queuedesk.risk;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic mock provider: seeded answers, bounded fake latency, no
 * network, no spend.
 *
 * <p>Answers mirror the snapshot features (the mock is context, not truth;
 * matched rules drive the policy), and the same input hash always yields
 * the same assessment, so scenario results are reproducible. Fault
 * injection covers the whole failure taxonomy on purpose: {@code TIMEOUT}
 * sleeps past the gate deadline, {@code INVALID} returns a response the
 * shared validation must reject, {@code UNAVAILABLE} throws before
 * answering, and {@code UNKNOWN} honestly abstains (Noul) on a required
 * question. This mock is the default provider bean; live mode replaces it.
 */
@Component
public class MockRiskSignalProvider implements RiskSignalProvider {
    /** Provider version stamped on mock assessments. */
    public static final String PROVIDER_VERSION = "mock-1.0";
    /** Model version stamped on mock assessments. */
    public static final String MODEL_VERSION = "mock-2026-10";

    private final RiskProperties properties;
    // Test seam: per-test fault without rebooting the context. Null means the
    // configured property applies.
    private final AtomicReference<String> faultForTest = new AtomicReference<>();
    // Call counter: proves bounded calls (RS05) and reuse without re-calling
    // (RS03). Tests reset it; production never reads it.
    private final java.util.concurrent.atomic.AtomicLong callsForTest = new java.util.concurrent.atomic.AtomicLong();

    public MockRiskSignalProvider(RiskProperties properties) {
        this.properties = properties;
    }

    /**
     * Validates a fault-injection setting ({@code NONE|TIMEOUT|INVALID|UNAVAILABLE|UNKNOWN}).
     *
     * @throws IllegalArgumentException naming the valid set for anything else
     */
    public static String requireFault(String raw) {
        if (raw == null) return "NONE";
        String name = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("NONE", "TIMEOUT", "INVALID", "UNAVAILABLE", "UNKNOWN").contains(name))
            throw new IllegalArgumentException("queuedesk.risk.mock-fault must be NONE|TIMEOUT|INVALID|UNAVAILABLE|UNKNOWN");
        return name;
    }

    /** Test seam: override the configured fault without rebooting the context. */
    public void setFaultForTest(String fault) { faultForTest.set(requireFault(fault)); }
    /** Test seam: drop the override so the configured fault applies again. */
    public void clearFaultForTest() { faultForTest.set(null); }
    /** Test seam: how many assessments were requested (proves bounded calls and reuse). */
    public long callsForTest() { return callsForTest.get(); }
    /** Test seam: reset the call counter between tests. */
    public void resetCallsForTest() { callsForTest.set(0); }

    @Override
    public Assessment assess(FeatureSnapshot snapshot, String inputHash) throws ProviderException {
        callsForTest.incrementAndGet();
        String fault = faultForTest.get() != null ? faultForTest.get() : properties.mockFault();
        long seed = seedOf(inputHash);
        return switch (fault) {
            case "TIMEOUT" -> {
                sleepUninterruptibly(properties.providerDeadlineMs() + 500);
                throw new ProviderTimeout("mock slept past the gate deadline");
            }
            case "UNAVAILABLE" -> throw new ProviderUnavailable("mock fault latch: provider unreachable");
            case "INVALID" -> {
                // Missing Q02 and a broken distribution: validation must reject.
                var broken = new Assessment(UUID.randomUUID(), inputHash, PROVIDER_VERSION, MODEL_VERSION,
                        properties.questionVersion(),
                        List.of(new ChoiceSignal(Q01_NEW_RECIPIENT, "true", Map.of("true", 0.5), 0.5)),
                        List.of(), 5, "OK", null);
                RiskSignalProvider.validate(broken, properties.questionVersion());
                throw new IllegalStateException("unreachable: broken mock response passed validation");
            }
            case "UNKNOWN" -> {
                var assessment = new Assessment(UUID.randomUUID(), inputHash, PROVIDER_VERSION, MODEL_VERSION,
                        properties.questionVersion(),
                        List.of(choice(Q01_NEW_RECIPIENT, !snapshot.receiverSeenBefore(), seed)),
                        List.of(Q02_UNUSUAL_AMOUNT), latencyMs(seed), "OK", null);
                RiskSignalProvider.validate(assessment, properties.questionVersion());
                yield assessment;
            }
            default -> {
                var assessment = new Assessment(UUID.randomUUID(), inputHash, PROVIDER_VERSION, MODEL_VERSION,
                        properties.questionVersion(),
                        List.of(choice(Q01_NEW_RECIPIENT, !snapshot.receiverSeenBefore(), seed),
                                choice(Q02_UNUSUAL_AMOUNT, RiskPolicy.unusualAmount(snapshot), seed + 1)),
                        List.of(), latencyMs(seed), "OK", null);
                RiskSignalProvider.validate(assessment, properties.questionVersion());
                sleepUninterruptibly(assessment.latencyMs());
                yield assessment;
            }
        };
    }

    private static ChoiceSignal choice(String questionId, boolean answer, long seed) {
        // Seeded confidence in [0.90, 0.99]; the mock sets it equal to the
        // picked probability (valid, but no longer required — the live model
        // reports the two independently).
        double confidence = 0.90 + (Math.floorMod(seed * 0x9E3779B1L, 10) / 100.0);
        double rounded = Math.round(confidence * 100.0) / 100.0;
        String pick = Boolean.toString(answer);
        return new ChoiceSignal(questionId, pick,
                Map.of("true", pick.equals("true") ? rounded : 1.0 - rounded,
                        "false", pick.equals("false") ? rounded : 1.0 - rounded),
                rounded);
    }

    private static long latencyMs(long seed) {
        return 5 + Math.floorMod(seed, 21);
    }

    private static long seedOf(String inputHash) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(inputHash.getBytes(StandardCharsets.UTF_8));
            long seed = 0;
            for (int i = 0; i < 8; i++) seed = (seed << 8) | (digest[i] & 0xFF);
            return seed;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void sleepUninterruptibly(long ms) {
        long deadline = System.nanoTime() + ms * 1_000_000L;
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            try {
                Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
