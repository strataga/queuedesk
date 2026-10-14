package dev.queuedesk.http;

import dev.queuedesk.ops.CountingSpanExporter;
import dev.queuedesk.ops.LogRingBuffer;
import dev.queuedesk.ops.SpanRingBuffer;
import dev.queuedesk.ops.TelemetryCounters;
import dev.queuedesk.repository.InstructionRepository;
import dev.queuedesk.repository.PageCursor;
import dev.queuedesk.risk.LatencyBuckets;
import dev.queuedesk.risk.RiskAssessmentRepository;
import dev.queuedesk.risk.RiskGateService;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Telemetry reads for the dashboard: latency cards and series, sampled
 * traces with waterfall detail, exporter health, and the live log ring.
 *
 * <p>Percentiles come from locally observed latency buckets over the stated
 * window (Micrometer keeps no history server-side), and {@code n} is always
 * the real observed count. Spans come from a bounded in-memory ring, so an
 * unknown or evicted trace ID answers 404 honestly: sampled diagnostics
 * are not a durable audit trail. Nothing is backfilled. An empty window
 * reads {@code p95 = -1} with {@code n = 0}.
 */
@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {
    private static final AttributeKey<String> INSTRUCTION_ID = AttributeKey.stringKey("business.instruction.id");
    private static final int SAMPLE_PERCENT = 100;

    private final LatencyBuckets buckets;
    private final SpanRingBuffer ring;
    private final LogRingBuffer logs;
    private final TelemetryCounters counters;
    private final CountingSpanExporter exporter;
    private final InstructionRepository instructions;
    private final RiskAssessmentRepository assessments;
    private final RiskGateService gate;

    public TelemetryController(LatencyBuckets buckets, SpanRingBuffer ring, LogRingBuffer logs,
            TelemetryCounters counters, CountingSpanExporter exporter, InstructionRepository instructions,
            RiskAssessmentRepository assessments, RiskGateService gate) {
        this.buckets = buckets;
        this.ring = ring;
        this.logs = logs;
        this.counters = counters;
        this.exporter = exporter;
        this.instructions = instructions;
        this.assessments = assessments;
        this.gate = gate;
    }

    /** One latency card: p95 over the window, the observed count, and what the measurement covers. */
    public record LatencyCard(double p95Ms, long n, String note) {}
    /** Risk-provider latency; all values {@code null} except mode when the gate is OFF. */
    public record RiskLatency(Double p95Ms, Long n, String mode) {}
    /** Trace sampling summary: configured percent and distinct traces currently in the ring. */
    public record Sampling(int percent, long sampledInstructions) {}
    /** Span-exporter health: last success, recent throughput, and drop accounting. */
    public record ExporterView(String status, Instant lastExportAt, long spansSent15Min,
            long spansDropped, long bufferedSpans, String bufferedSpansNote) {}
    /** Telemetry summary response for the dashboard's telemetry page. */
    public record Summary(Instant asOf, int windowMinutes, LatencyCard admission, LatencyCard completion,
            RiskLatency riskProvider, Sampling sampling, ExporterView exporter) {}

    /**
     * Returns latency cards, sampling state, and exporter health for the
     * last {@code windowMinutes} (1–60). Admission latency ends at the HTTP
     * 202; completion latency ends at the durable outcome commit, with
     * unresolved payments excluded.
     */
    @GetMapping("/summary")
    public Summary summary(@RequestParam(defaultValue = "15") int windowMinutes) {
        window(windowMinutes);
        var admission = buckets.summary(LatencyBuckets.ADMISSION, windowMinutes);
        var completion = buckets.summary(LatencyBuckets.COMPLETION, windowMinutes);
        RiskLatency risk;
        if (gate.mode() == dev.queuedesk.risk.RiskMode.OFF) {
            risk = new RiskLatency(null, null, "OFF");
        } else {
            var provider = buckets.summary(LatencyBuckets.RISK_PROVIDER, windowMinutes);
            risk = new RiskLatency(provider.p95Ms(), provider.n(), gate.mode().name());
        }
        Set<String> traces = new HashSet<>();
        for (var span : ring.spans()) traces.add(span.getSpanContext().getTraceId());
        String status = exporter.failedRecently() ? "DEGRADED"
                : (exporter.lastOkAt() == null && counters.exporterFailedCount() > 0 ? "DOWN" : "HEALTHY");
        return new Summary(Instant.now(), windowMinutes,
                new LatencyCard(admission.p95Ms(), admission.n(), "ends at HTTP 202"),
                new LatencyCard(completion.p95Ms(), completion.n(),
                        "ends at durable outcome commit; unresolved excluded"),
                risk,
                new Sampling(SAMPLE_PERCENT, traces.size()),
                new ExporterView(status, exporter.lastOkAt(), exporter.spansSent15Min(),
                        (long) (counters.exporterFailedCount() + counters.bufferDroppedCount()),
                        0, "SDK batch-queue depth is not exposed; ring evictions count in spansDropped"));
    }

    /** One per-minute latency point for charting. */
    public record SeriesPoint(Instant at, double p95Ms, long n) {}
    /** Latency-series response for one metric. */
    public record Series(String metric, int bucketSeconds, List<SeriesPoint> points) {}

    /**
     * Returns the per-minute p95 series for one metric
     * ({@code admission}, {@code completion}, or {@code riskProvider}).
     * Unknown metric names are a 400, never an empty series.
     */
    @GetMapping("/latency-series")
    public Series series(@RequestParam String metric,
            @RequestParam(defaultValue = "15") int windowMinutes) {
        window(windowMinutes);
        String name = switch (metric) {
            case "admission" -> LatencyBuckets.ADMISSION;
            case "completion" -> LatencyBuckets.COMPLETION;
            case "riskProvider" -> LatencyBuckets.RISK_PROVIDER;
            default -> throw new IllegalArgumentException("metric must be admission|completion|riskProvider");
        };
        var points = buckets.series(name, windowMinutes).stream()
                .map(p -> new SeriesPoint(p.at(), p.p95Ms(), p.n())).toList();
        return new Series(metric, 60, points);
    }

    /** One sampled trace, joined (when possible) to its instruction's reference and outcome. */
    public record TraceItem(String instructionId, String clientReference, String traceId,
            String outcome, Long elapsedMs) {}
    /** Trace-list response wrapper. */
    public record TraceList(List<TraceItem> items) {}

    /**
     * Lists sampled traces, newest first: one row per trace, taken from its
     * latest worker-attempt span. Rows whose instruction join is unavailable
     * keep {@code null} reference and outcome instead of being dropped.
     */
    @GetMapping("/traces")
    public ResponseEntity<TraceList> traces(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        int offset = decodeOffset(cursor);
        // One row per trace: latest worker-attempt span per traceId, newest first.
        var byTrace = new HashMap<String, SpanData>();
        for (var span : ring.spans()) {
            if (!"queuedesk.worker.attempt".equals(span.getName())) continue;
            String traceId = span.getSpanContext().getTraceId();
            var prev = byTrace.get(traceId);
            if (prev == null || span.getStartEpochNanos() > prev.getStartEpochNanos()) byTrace.put(traceId, span);
        }
        var ordered = byTrace.values().stream()
                .sorted((a, b) -> Long.compare(b.getStartEpochNanos(), a.getStartEpochNanos())).toList();
        var items = new ArrayList<TraceItem>();
        for (int i = offset; i < Math.min(offset + limit + 1, ordered.size()); i++) {
            var span = ordered.get(i);
            String iid = span.getAttributes().get(INSTRUCTION_ID);
            String ref = null;
            String outcome = null;
            if (iid != null) {
                try {
                    var stored = instructions.byId(UUID.fromString(iid));
                    if (stored.isPresent()) {
                        ref = stored.get().payload().clientReference();
                        outcome = stored.get().state();
                    }
                } catch (IllegalArgumentException notUuid) {
                    // Transport-only span attribute; leave the join null.
                }
            }
            long elapsedMs = (span.getEndEpochNanos() - span.getStartEpochNanos()) / 1_000_000L;
            items.add(new TraceItem(iid, ref, span.getSpanContext().getTraceId(), outcome, elapsedMs));
        }
        boolean more = items.size() > limit;
        var page = more ? items.subList(0, limit) : items;
        var response = ResponseEntity.ok();
        if (more) response.header("X-Next-Cursor", encodeOffset(offset + limit));
        return response.body(new TraceList(page));
    }

    /** One waterfall span, positioned relative to the trace start. */
    public record SpanView(String spanId, String parentSpanId, String name, long startOffsetMs,
            long durationMs, Map<String, String> attributes) {}
    /** A cross-process link between spans (reserved; currently always empty). */
    public record AsyncLink(String fromSpanId, String toSpanId, String via) {}
    /** Trace detail response: business context plus the full span waterfall. */
    public record TraceDetail(String traceId, String instructionId, String clientReference, String participants,
            int sampledPercent, String businessOutcome, String riskDecision, String riskMode, long endToEndMs,
            Long admissionMs, List<SpanView> spans, List<AsyncLink> asyncLinks, String auditUrl) {}

    /**
     * Returns one trace as a waterfall, joined to its instruction's current
     * business outcome and risk decision. Answers 400 for a malformed trace
     * ID and 404 when the trace was never sampled or has been evicted.
     */
    @GetMapping("/traces/{traceId}")
    public TraceDetail trace(@PathVariable String traceId) {
        if (traceId == null || !traceId.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("traceId must be 32 lowercase hex characters");
        var spans = ring.spans().stream()
                .filter(s -> traceId.equals(s.getSpanContext().getTraceId())).toList();
        if (spans.isEmpty()) throw new dev.queuedesk.service.AdmissionService.Missing();
        long start = spans.stream().mapToLong(SpanData::getStartEpochNanos).min().orElse(0);
        long end = spans.stream().mapToLong(SpanData::getEndEpochNanos).max().orElse(start);
        var views = spans.stream()
                .sorted((a, b) -> Long.compare(a.getStartEpochNanos(), b.getStartEpochNanos()))
                .map(s -> new SpanView(s.getSpanContext().getSpanId(),
                        s.getParentSpanContext().isValid() ? s.getParentSpanContext().getSpanId() : null,
                        s.getName(), (s.getStartEpochNanos() - start) / 1_000_000L,
                        (s.getEndEpochNanos() - s.getStartEpochNanos()) / 1_000_000L,
                        stringify(s.getAttributes().asMap())))
                .toList();
        String iid = spans.stream().map(s -> s.getAttributes().get(INSTRUCTION_ID))
                .filter(id -> id != null).findFirst().orElse(null);
        String ref = null;
        String participants = null;
        String outcome = null;
        String riskDecision = null;
        String auditUrl = null;
        if (iid != null) {
            try {
                var stored = instructions.byId(UUID.fromString(iid));
                if (stored.isPresent()) {
                    var p = stored.get().payload();
                    ref = p.clientReference();
                    participants = p.senderParticipant() + " → " + p.receiverParticipant();
                    outcome = stored.get().state();
                    auditUrl = stored.get().statusUrl();
                    riskDecision = assessments.latestByInstruction(UUID.fromString(iid))
                            .map(RiskAssessmentRepository.StoredAssessment::policyResult).orElse(null);
                }
            } catch (IllegalArgumentException notUuid) {
                // Leave the join null.
            }
        }
        Long admissionMs = spans.stream()
                .filter(s -> "POST /api/v1/instructions".equals(s.getName())).findFirst()
                .map(s -> (s.getEndEpochNanos() - s.getStartEpochNanos()) / 1_000_000L).orElse(null);
        return new TraceDetail(traceId, iid, ref, participants, SAMPLE_PERCENT, outcome,
                gate.mode() == dev.queuedesk.risk.RiskMode.OFF ? null : riskDecision,
                gate.mode().name(), (end - start) / 1_000_000L, admissionMs, views, List.of(), auditUrl);
    }

    private static void window(int windowMinutes) {
        if (windowMinutes < 1 || windowMinutes > 60)
            throw new IllegalArgumentException("windowMinutes must be 1..60");
    }

    /** Log-ring response: the newest entries plus drop accounting for the bounded buffer. */
    public record LogList(java.util.List<LogRingBuffer.Entry> items, long dropped,
            int capacity, String note) {}

    /**
     * Returns the tail of this process's live log ring, newest last, at or
     * above the requested level. The ring is per-process and clears on
     * restart; entries carry no stack traces by design.
     */
    @GetMapping("/logs")
    public LogList logs(@RequestParam(required = false) String level,
            @RequestParam(defaultValue = "100") int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be 1..500");
        var threshold = level == null ? ch.qos.logback.classic.Level.DEBUG
                : ch.qos.logback.classic.Level.toLevel(level.toUpperCase(java.util.Locale.ROOT), null);
        if (threshold == null) throw new IllegalArgumentException("level must be DEBUG, INFO, WARN or ERROR");
        var items = logs.entries().stream()
                .filter(e -> ch.qos.logback.classic.Level.toLevel(e.level()).levelInt >= threshold.levelInt)
                .toList();
        var tail = items.subList(Math.max(0, items.size() - limit), items.size());
        return new LogList(tail, logs.dropped(), logs.capacity(),
                "live ring of this process only; restarts clear it, no stack traces are kept");
    }

    private static int decodeOffset(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            int offset = Integer.parseInt(new String(java.util.Base64.getUrlDecoder().decode(cursor),
                    java.nio.charset.StandardCharsets.UTF_8));
            if (offset < 0) throw new PageCursor.BadCursor();
            return offset;
        } catch (IllegalArgumentException bad) {
            throw new PageCursor.BadCursor();
        }
    }

    private static String encodeOffset(int offset) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                Integer.toString(offset).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Map<String, String> stringify(Map<AttributeKey<?>, Object> attributes) {
        var out = new HashMap<String, String>();
        attributes.forEach((key, value) -> out.put(key.getKey(), String.valueOf(value)));
        return Map.copyOf(out);
    }
}
