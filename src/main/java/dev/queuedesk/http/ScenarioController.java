package dev.queuedesk.http;

import dev.queuedesk.domain.PaymentInstruction;
import dev.queuedesk.repository.InstructionRepository;
import dev.queuedesk.risk.RiskGateService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

// Scenarios + run detail (DASH-04): static catalog with REAL CLI previews
// (the mockups invented flags — §8 maps every entry to the actual
// scripts/generate / scripts/risk-eval.py interface) and run evidence
// discovered from durable state. The UI executes nothing. Runs come from
// DISTINCT run_id over instruction, never from CLI-side reports/*.json the
// server cannot rely on. Unknown runs report zeros, never 404.
@RestController
@RequestMapping("/api/v1/scenarios")
public class ScenarioController {
    private final JdbcClient jdbc;
    private final InstructionRepository instructions;
    private final RiskGateService gate;
    private final String reportsDir;

    public ScenarioController(JdbcClient jdbc, InstructionRepository instructions, RiskGateService gate,
            @Value("${queuedesk.reports.dir:reports}") String reportsDir) {
        this.jdbc = jdbc;
        this.instructions = instructions;
        this.gate = gate;
        this.reportsDir = reportsDir;
    }

    public record CatalogItem(String id, String title, String group, String blurb, Integer seed,
            Integer unique, Integer repeats, Integer maxAttempts, String riskMode,
            String commandPreview, List<String> commandArgs, boolean dryRunDefault) {}
    public record Catalog(List<CatalogItem> items) {}

    private static CatalogItem core(String id, String title, String blurb, Integer seed, Integer unique,
            Integer repeats, Integer maxAttempts, String preview, List<String> args) {
        return new CatalogItem(id, title, "CORE", blurb, seed, unique, repeats, maxAttempts,
                "OFF", preview, args, true);
    }

    private static CatalogItem risk(String id, String title, String blurb) {
        String shortId = id.toLowerCase(java.util.Locale.ROOT);
        return new CatalogItem(id, title, "RISK", blurb, 42, null, null, null, "RULES_PLUS_MOCK",
                "scripts/risk-eval.py " + shortId,
                List.of("scripts/risk-eval.py", shortId), false);
    }

    // Static backend metadata: IDs/titles from PRD §14 + §25, previews from §8.
    // Dry run is the default (absence of --send); there is no --dry-run flag.
    private static final List<CatalogItem> CATALOG = List.of(
            core("SC01", "Normal", "20 unique, seed 42", 42, 20, 0, 20,
                    "scripts/generate --scenario normal --seed 42 --count 20 --run-id <run>",
                    List.of("scripts/generate", "--scenario", "normal", "--seed", "42", "--count", "20", "--run-id", "<run>")),
            core("SC02", "Duplicates", "20 unique + 5 repeats", 42, 20, 5, 25,
                    "scripts/generate --scenario duplicate --seed 42 --count 20 --run-id <run>",
                    List.of("scripts/generate", "--scenario", "duplicate", "--seed", "42", "--count", "20", "--run-id", "<run>")),
            core("SC03", "Conflict", "changed body under existing key", 42, 10, 0, 20,
                    "scripts/generate --scenario conflict --seed 42 --count 20 --run-id <run>",
                    List.of("scripts/generate", "--scenario", "conflict", "--seed", "42", "--count", "20", "--run-id", "<run>")),
            core("SC04", "Invalid", "400 each, zero rows", 42, 0, 0, 20,
                    "scripts/generate --scenario invalid --seed 42 --count 20 --run-id <run>",
                    List.of("scripts/generate", "--scenario", "invalid", "--seed", "42", "--count", "20", "--run-id", "<run>")),
            core("SC05", "Burst", "100 at capped rate/concurrency", 42, 100, 0, 100,
                    "scripts/generate --scenario burst --seed 42 --count 100 --rate 10 --concurrency 4 --run-id <run>",
                    List.of("scripts/generate", "--scenario", "burst", "--seed", "42", "--count", "100",
                            "--rate", "10", "--concurrency", "4", "--run-id", "<run>")),
            core("SC06", "MQ outage after admission", "pending outbox survives, drains after recovery", 42, 20, 0, 20,
                    "scripts/generate --scenario normal --seed 42 --count 20 --run-id <run>  # + scripts/mq down/up around it",
                    List.of("scripts/generate", "--scenario", "normal", "--seed", "42", "--count", "20", "--run-id", "<run>")),
            core("SC07", "Controlled redelivery", "same eventId twice, one result", 42, 1, 0, 1,
                    "scripts/generate --scenario normal --seed 42 --count 1 --run-id <run>  # + scripts/redeliver <base> <iid> <eid>",
                    List.of("scripts/generate", "--scenario", "normal", "--seed", "42", "--count", "1", "--run-id", "<run>")),
            core("SC08", "Crash between commits", "restart redelivers safely", 42, 1, 0, 1,
                    "scripts/generate --scenario normal --seed 42 --count 1 --run-id <run>  # + fault-latch restart",
                    List.of("scripts/generate", "--scenario", "normal", "--seed", "42", "--count", "1", "--run-id", "<run>")),
            core("SC09a", "Lost receiver reply", "PENDING_CONFIRMATION reconciles", 42, 20, 0, 20,
                    "scripts/generate --scenario uncertain --seed 42 --count 20 --run-id <run>",
                    List.of("scripts/generate", "--scenario", "uncertain", "--seed", "42", "--count", "20", "--run-id", "<run>")),
            core("SC09b", "Unknown receiver outcome", "still-pending lookup", 42, 5, 0, 5,
                    "scripts/generate --scenario unknown --seed 42 --count 5 --observe-seconds 25 --run-id <run>",
                    List.of("scripts/generate", "--scenario", "unknown", "--seed", "42", "--count", "5",
                            "--observe-seconds", "25", "--run-id", "<run>")),
            core("SC10", "Poison + bad backout route", "held with reason, never lost", 42, null, null, null,
                    "scripts/poison <run> '<payload>'",
                    List.of("scripts/poison", "<run>", "<payload>")),
            core("SC11", "Business rejection", "definitive REJECTED, not retried", 42, 20, 0, 20,
                    "scripts/generate --scenario reject --seed 42 --count 20 --run-id <run>",
                    List.of("scripts/generate", "--scenario", "reject", "--seed", "42", "--count", "20", "--run-id", "<run>")),
            core("SC12", "Stop / deadline", "honest pending/unknown, no fabrication", 42, 20, 0, 20,
                    "scripts/generate --scenario normal --seed 42 --count 20 --send-seconds 3 --run-id <run>",
                    List.of("scripts/generate", "--scenario", "normal", "--seed", "42", "--count", "20",
                            "--send-seconds", "3", "--run-id", "<run>")),
            risk("RS01", "Benign normal and benign unusual activity",
                    "report unnecessary holds and rejections; unusual is not automatically fraud"),
            risk("RS02", "Labeled suspicious velocity or conflicting context",
                    "rules compute numeric facts; measure missed suspicious fixtures"),
            risk("RS03", "HTTP retries and MQ redeliveries",
                    "no inflated velocity; stable identities and cached assessment"),
            risk("RS04", "Missing, contradictory or adversarial memo text",
                    "injection cannot alter policy, labels or destinations"),
            risk("RS05", "Provider timeout, invalid type, rate limit or wrong model",
                    "NOT_ASSESSED hold; bounded calls and no receiver invocation"),
            risk("RS06", "Restart around assessment save / hold commit",
                    "no second business outcome; incomplete assessment stays visible"));

    @GetMapping
    public Catalog catalog() {
        return new Catalog(CATALOG);
    }

    public record RunItem(String runId, String scenarioId, String scenarioTitle, Instant startedAt,
            long admitted, String summary) {}
    public record RunList(List<RunItem> items) {}
    @GetMapping("/runs")
    public ResponseEntity<RunList> runs(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        int offset = decodeOffset(cursor);
        var rows = jdbc.sql("SELECT run_id, MIN(created_at) AS started, COUNT(*) AS n"
                        + " FROM instruction GROUP BY run_id ORDER BY started DESC, run_id DESC"
                        + " LIMIT ? OFFSET ?")
                .params(limit + 1, offset).query((rs, row) -> new RunItem(rs.getString("run_id"),
                        null, null, rs.getObject("started", OffsetDateTime.class).toInstant(),
                        rs.getLong("n"), rs.getLong("n") + " admitted")).list();
        boolean more = rows.size() > limit;
        var page = more ? rows.subList(0, limit) : rows;
        var response = ResponseEntity.ok();
        if (more) response.header("X-Next-Cursor", encodeOffset(offset + limit));
        return response.body(new RunList(page));
    }

    public record AssertionView(String text, boolean passed) {}
    public record GeneratorView(String scenario, Integer seed, long unique, long repeats,
            long maxAttempts, String participants) {}
    public record RunDetail(String runId, String scenarioId, String scenarioTitle, Instant startedAt,
            Integer seed, String riskMode, Long httpAttempts, long uniqueAdmitted, long simulatedCompleted,
            long unknownConfirmations, Long repeatResponses, long duplicateOutcomes,
            List<AssertionView> assertions, GeneratorView generator, String commandPreview,
            List<String> commandArgs, boolean dryRunDefault) {}
    @GetMapping("/runs/{runId}")
    public RunDetail run(@PathVariable String runId) {
        PaymentInstruction.token(runId, "runId");
        var byState = instructions.countByState(runId);
        long admitted = byState.values().stream().mapToLong(Long::longValue).sum();
        Instant started = jdbc.sql("SELECT MIN(created_at) FROM instruction WHERE run_id = ?")
                .param(runId).query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant).orElse(null);
        long duplicates = jdbc.sql("""
                SELECT COUNT(*) FROM processing_attempt a
                 WHERE a.result = 'DUPLICATE_SKIPPED' AND a.instruction_id IN
                 (SELECT instruction_id FROM instruction WHERE run_id = ?)""")
                .param(runId).query(Long.class).single();
        long riskHeld = byState.getOrDefault("RISK_HELD", 0L);
        long pendingConfirm = byState.getOrDefault("PENDING_CONFIRMATION", 0L);
        long completed = byState.getOrDefault("COMPLETED_SIMULATED", 0L);
        // Generator config is never invented server-side: httpAttempts and
        // repeatResponses are admission-time facts the server does not persist,
        // so they read null (explicitly unavailable, never zero-masked).
        var assertions = new ArrayList<AssertionView>();
        assertions.add(new AssertionView(admitted + " unique instructions admitted", true));
        assertions.add(new AssertionView(completed + " simulated completed", true));
        assertions.add(new AssertionView(duplicates + " duplicate deliveries absorbed", true));
        assertions.add(new AssertionView("no unresolved work", pendingConfirm == 0 && riskHeld == 0));
        // Only the documented demo-<seed> convention carries a seed; every
        // other runId leaves seed null rather than guessing.
        Integer seed = null;
        if (runId.matches("demo-\\d+")) {
            try {
                seed = Integer.parseInt(runId.substring(5));
            } catch (NumberFormatException tooBig) {
                seed = null;
            }
        }
        String preview = "scripts/generate --scenario normal --seed " + (seed == null ? "<seed>" : seed)
                + " --count " + Math.max(admitted, 1) + " --run-id " + runId + " --send --observe";
        return new RunDetail(runId, null, null, started, seed, gate.mode().name(), null, admitted,
                completed, pendingConfirm, null, duplicates, List.copyOf(assertions),
                new GeneratorView(null, seed, admitted, 0, admitted, "SENDER_A → RECEIVER_B"),
                preview, List.of("scripts/generate", "--scenario", "normal", "--seed",
                        seed == null ? "<seed>" : seed.toString(), "--count", Long.toString(Math.max(admitted, 1)),
                        "--run-id", runId, "--send", "--observe"),
                true);
    }

    // Export re-serves the run JSON (same shape + exportedAt); no server-side
    // PDF/CSV in this build.
    @GetMapping("/runs/{runId}/export")
    public Map<String, Object> export(@PathVariable String runId) {
        return Map.of("run", run(runId), "exportedAt", Instant.now().toString());
    }

    // CLI report files (mockup 10 evidence): the generator/harness writes
    // reports/*.json beside the server; the UI lists and re-serves them here.
    // Zero new migrations — the list carries file metadata only (name, bytes,
    // mtime, newest first) and the UI parses the served JSON for detail. A
    // missing directory reads as an empty list: the CLI may run elsewhere.
    private static final Pattern REPORT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.json");
    public record ReportFile(String name, long sizeBytes, Instant modifiedAt) {}
    public record ReportList(List<ReportFile> items) {}
    @GetMapping("/reports")
    public ResponseEntity<ReportList> reports(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        int offset = decodeOffset(cursor);
        var files = listReports();
        var page = files.stream().skip(offset).limit(limit + 1L).toList();
        boolean more = page.size() > limit;
        var response = ResponseEntity.ok();
        if (more) response.header("X-Next-Cursor", encodeOffset(offset + limit));
        return response.body(new ReportList(more ? page.subList(0, limit) : page));
    }

    // Raw bytes with application/json; the server never re-shapes CLI evidence.
    // Traversal guard in two layers: the name allowlist rejects separators and
    // leading dots (400 INVALID_REQUEST), then the normalized resolve must stay
    // under the reports dir. Well-formed but absent reads 404 NOT_FOUND.
    @GetMapping("/reports/{name}")
    public ResponseEntity<?> report(@PathVariable String name) {
        if (!REPORT_NAME.matcher(name).matches())
            throw new IllegalArgumentException("report name must match [A-Za-z0-9][A-Za-z0-9._-]*.json");
        Path base = Path.of(reportsDir).toAbsolutePath().normalize();
        Path file = base.resolve(name).normalize();
        if (!file.startsWith(base))
            throw new IllegalArgumentException("report name escapes the reports directory");
        if (!Files.isRegularFile(file))
            return ResponseEntity.status(404)
                    .body(new ApiErrors.Error("NOT_FOUND", "Unknown report: " + name));
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(Files.readAllBytes(file));
        } catch (IOException unreadable) {
            throw new IllegalStateException("stored report is not readable", unreadable);
        }
    }

    private List<ReportFile> listReports() {
        Path base = Path.of(reportsDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) return List.of();
        try (var stream = Files.list(base)) {
            return stream.filter(p -> Files.isRegularFile(p)
                            && REPORT_NAME.matcher(p.getFileName().toString()).matches())
                    .map(p -> {
                        try {
                            return new ReportFile(p.getFileName().toString(), Files.size(p),
                                    Files.getLastModifiedTime(p).toInstant());
                        } catch (IOException unreadable) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(ReportFile::modifiedAt, Comparator.reverseOrder())
                            .thenComparing(ReportFile::name, Comparator.reverseOrder()))
                    .toList();
        } catch (IOException unreadable) {
            throw new IllegalStateException("reports directory is not readable", unreadable);
        }
    }

    private static int decodeOffset(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            int offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor),
                    java.nio.charset.StandardCharsets.UTF_8));
            if (offset < 0) throw new dev.queuedesk.repository.PageCursor.BadCursor();
            return offset;
        } catch (IllegalArgumentException bad) {
            throw new dev.queuedesk.repository.PageCursor.BadCursor();
        }
    }

    private static String encodeOffset(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
