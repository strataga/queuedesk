package dev.queuedesk.risk;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

// Jev adapter configuration (live gate, stubbed phase): endpoint, pinned
// model, credential, deadline. Plain validated holder — NOT a Spring bean yet:
// RULES_PLUS_JEV still fails boot validation (PR16), so nothing constructs
// this in production. Tests build it directly; the live gate adds Spring
// binding plus the mode flip. The credential never appears in toString, logs,
// or reports.
public record JevProperties(String baseUrl, String model, String apiKey, long deadlineMs) {
    // TypeSafe list price (docs/models, fetched 6 Oct 2026): $42 per billion
    // input tokens, output free. Nano-dollars keep small lab spends exact.
    public static final long NANO_USD_PER_INPUT_TOKEN = 42;

    public static long costNanoUsd(long inputTokens) {
        return Math.multiplyExact(inputTokens, NANO_USD_PER_INPUT_TOKEN);
    }
    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
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
