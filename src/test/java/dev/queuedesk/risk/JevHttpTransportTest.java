package dev.queuedesk.risk;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// Real-transport tests: JevHttpTransport against a loopback stub server —
// status mapping, auth header, deadline and the no-retry rule. The only
// network in this phase is 127.0.0.1 within the test JVM.
class JevHttpTransportTest {
    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final List<String> authorizations = new ArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile long delayMs = 0;

    @BeforeEach void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            hits.incrementAndGet();
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
    }

    @AfterEach void stopStub() {
        server.stop(0);
    }

    private JevHttpTransport transport(long deadlineMs) {
        return new JevHttpTransport(new JevProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), "jev-1.13.0", "test-key", deadlineMs));
    }

    @Test void postsJsonWithBearerAuth() throws Exception {
        body = "{\"model\":\"jev-1.13.0\"}";
        assertEquals(body, transport(2000).evaluate("{\"model\":\"jev-1.13.0\"}"));
        assertEquals(List.of("Bearer test-key"), authorizations);
        assertEquals(1, hits.get());
    }

    @Test void mapsFaultStatuses() {
        record Case(int status, Class<? extends RiskSignalProvider.ProviderException> type, String fragment) {}
        for (var kase : new Case[] {
                new Case(401, RiskSignalProvider.ProviderUnavailable.class, "unauthorized"),
                new Case(422, RiskSignalProvider.InvalidResponse.class, "rejected"),
                new Case(429, RiskSignalProvider.ProviderUnavailable.class, "no automatic retry"),
                new Case(529, RiskSignalProvider.ProviderUnavailable.class, "no automatic retry"),
                new Case(500, RiskSignalProvider.ProviderUnavailable.class, "status 500")}) {
            status = kase.status();
            body = "{\"error\":\"boom\"}";
            var failed = assertThrows(kase.type(),
                    () -> transport(2000).evaluate("{}"), "status " + kase.status());
            assertTrue(failed.detail().contains(kase.fragment()), failed.detail());
        }
        assertEquals(5, hits.get(), "one call per evaluate: no retries");
    }

    @Test void emptySuccessBodyIsInvalid() {
        body = "  ";
        assertThrows(RiskSignalProvider.InvalidResponse.class, () -> transport(2000).evaluate("{}"));
    }

    @Test void slowServerBeatsTheDeadline() {
        delayMs = 1500;
        assertThrows(RiskSignalProvider.ProviderTimeout.class, () -> transport(150).evaluate("{}"));
    }

    @Test void refusedConnectionIsUnavailable() {
        int deadPort = server.getAddress().getPort();
        server.stop(0);
        var failed = assertThrows(RiskSignalProvider.ProviderUnavailable.class,
                () -> new JevHttpTransport(new JevProperties(
                        "http://127.0.0.1:" + deadPort, "jev-1.13.0", "test-key", 2000)).evaluate("{}"));
        assertTrue(failed.detail().contains("unreachable"), failed.detail());
    }

    @Test void failureMessagesNeverCarryTheCredential() {
        status = 500;
        body = "negative";
        var failed = assertThrows(RiskSignalProvider.ProviderUnavailable.class,
                () -> transport(2000).evaluate("{}"));
        assertFalse(failed.detail().contains("test-key"));
        assertFalse(failed.getMessage().contains("test-key"));
    }
}
