package dev.queuedesk.risk;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validated configuration for the live Jev adapter: endpoint, pinned model,
 * credential, and deadline.
 *
 * <p>Plain holder, built by {@link RiskLiveConfig} in live mode and
 * directly by tests. The compact constructor refuses anything unsafe: a
 * non-HTTPS endpoint (loopback HTTP excepted, for stub-server tests), an
 * unpinned model alias such as {@code jev-latest}, a missing key, or an
 * out-of-range deadline. The credential never appears in
 * {@link #toString}, logs, or reports.
 */
public record JevProperties(String baseUrl, String model, String apiKey, long deadlineMs) {
    /**
     * TypeSafe list price at lab build time: $42 per billion input tokens,
     * output free. Nano-dollars keep tiny lab spends exact in integer math.
     */
    public static final long NANO_USD_PER_INPUT_TOKEN = 42;

    /** Exact list-price cost in nano-dollars for a token count (overflows loudly, never wraps). */
    public static long costNanoUsd(long inputTokens) {
        return Math.multiplyExact(inputTokens, NANO_USD_PER_INPUT_TOKEN);
    }
    /** Default TypeSafe API origin. */
    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
    /** Evaluation endpoint path, appended to the configured base URL. */
    public static final String PATH = "/v1/systemone";
    private static final Pattern PINNED_MODEL = Pattern.compile("jev-\\d+\\.\\d+(\\.\\d+)?");

    public JevProperties {
        if (baseUrl == null || baseUrl.isBlank())
            throw new IllegalArgumentException("jev baseUrl is required");
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException bad) {
            throw new IllegalArgumentException("jev baseUrl is not a URI: " + baseUrl);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")
                || host.equals("[::1]");
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback))
            throw new IllegalArgumentException(
                    "jev baseUrl must be https (http only for loopback tests): " + baseUrl);
        if (model == null || !PINNED_MODEL.matcher(model.trim()).matches())
            throw new IllegalArgumentException(
                    "jev model must be pinned like jev-1.13.0, never the jev-latest alias: " + model);
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalArgumentException("jev apiKey is required (never committed, never logged)");
        if (deadlineMs < 50 || deadlineMs > 30_000)
            throw new IllegalArgumentException("jev deadlineMs must be 50..30000");
    }

    /** Returns the full evaluation URL (base plus path, single slash). */
    public String endpoint() {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + PATH;
    }

    @Override
    public String toString() {
        return "JevProperties[baseUrl=" + baseUrl + ", model=" + model + ", apiKey=<redacted>"
                + ", deadlineMs=" + deadlineMs + "]";
    }
}
