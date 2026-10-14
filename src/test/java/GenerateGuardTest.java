import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

// SAFE-01 generator side, end to end: against a guards endpoint reporting a
// halt, the real scripts/generate sends nothing, writes an honest report and
// exits 2. An unreadable guard state (unreachable endpoint, non-200,
// malformed halt body) fails CLOSED the same way — a safeguard that cannot
// be read cannot be trusted.
class GenerateGuardTest {
    @TempDir Path tmp;
    @Test void haltsBeforeAnySend() throws Exception {
        var guards = server(200, "{\"haltTraffic\":true,\"reasons\":[\"outbox backlog 5 exceeds limit 0\"]}");
        try {
            String out = runGenerate(guards.getAddress().getPort(), "guard-halt", "halt.json");
            assertTrue(out.contains("guard.halt"), out);
            assertTrue(out.contains("backlog 5 exceeds limit 0"), out);
        } finally {
            guards.stop(0);
        }
    }
    @Test void unreachableEndpointFailsClosed() throws Exception {
        var guards = server(200, "{}");
        int port = guards.getAddress().getPort();
        guards.stop(0);
        String out = runGenerate(port, "guard-dark", "dark.json");
        assertTrue(out.contains("fail closed"), out);
    }
    @Test void malformedHaltBodyFailsClosed() throws Exception {
        var guards = server(200, "{\"haltTraffic\":true}");
        try {
            String out = runGenerate(guards.getAddress().getPort(), "guard-malformed", "malformed.json");
            assertTrue(out.contains("guard.halt"), out);
        } finally {
            guards.stop(0);
        }
    }
    private String runGenerate(int port, String runId, String reportName) throws Exception {
        Path report = tmp.resolve(reportName);
        var process = new ProcessBuilder("scripts/generate", "--send", "--count", "3",
                "--run-id", runId, "--base-url", "http://127.0.0.1:" + port,
                "--report", report.toString())
                .directory(Path.of(".").toAbsolutePath().toFile())
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "generate timed out");
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(2, process.exitValue(), out);
        String json = Files.readString(report);
        assertTrue(json.contains("\"unsent\": 3"), json);
        assertTrue(json.contains(":guard-halted"), json);
        assertTrue(json.contains("\"attempted\": 0"), json);
        return out;
    }
    private static HttpServer server(int status, String json) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/ops/guards", exchange -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        return server;
    }
}
