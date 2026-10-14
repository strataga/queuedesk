package dev.queuedesk.risk;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

// Production Jev transport (live gate, stubbed phase): real java.net.http
// POSTs to the pinned endpoint with the bounded deadline. NOT a Spring bean
// (the mock stays the only RiskSignalProvider bean until the live gate) —
// tests run it against a loopback stub server. No retries: 429/529 surface
// as unavailable per PRD §25. Provider error bodies are bounded before they
// reach messages; the API key never appears in any message.
public class JevHttpTransport implements JevTransport {
    private static final int MAX_DETAIL = 500;

    private final JevProperties properties;
    private final HttpClient client;

    public JevHttpTransport(JevProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.deadlineMs()))
                .build());
    }

    JevHttpTransport(JevProperties properties, HttpClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public String evaluate(String requestJson) throws RiskSignalProvider.ProviderException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.endpoint()))
                .timeout(Duration.ofMillis(properties.deadlineMs()))
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException timedOut) {
            throw new RiskSignalProvider.ProviderTimeout(
                    "jev deadline exceeded (" + properties.deadlineMs() + "ms)");
        } catch (ConnectException refused) {
            throw new RiskSignalProvider.ProviderUnavailable(
                    "jev unreachable: " + detail(refused.getMessage()));
        } catch (java.io.IOException failed) {
            throw new RiskSignalProvider.ProviderUnavailable(
                    "jev IO failure: " + detail(failed.getMessage()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RiskSignalProvider.ProviderTimeout("jev call interrupted");
        }
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (status >= 200 && status < 300) {
            if (body.isBlank()) throw new RiskSignalProvider.InvalidResponse("jev returned an empty body");
            return body;
        }
        switch (status) {
            case 401 -> throw new RiskSignalProvider.ProviderUnavailable(
                    "jev unauthorized (401): check the API key");
            case 422 -> throw new RiskSignalProvider.InvalidResponse(
                    "jev rejected the request (422): " + detail(body));
            case 429, 529 -> throw new RiskSignalProvider.ProviderUnavailable(
                    "jev overloaded (status " + status + "): no automatic retry (PRD §25)");
            default -> throw new RiskSignalProvider.ProviderUnavailable(
                    "jev status " + status + ": " + detail(body));
        }
    }

    private static String detail(String message) {
        if (message == null || message.isBlank()) return "<no detail>";
        String singleLine = message.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= MAX_DETAIL ? singleLine : singleLine.substring(0, MAX_DETAIL) + "…";
    }
}
