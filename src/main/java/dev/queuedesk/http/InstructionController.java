package dev.queuedesk.http;

import dev.queuedesk.domain.*;
import dev.queuedesk.ops.LogContext;
import dev.queuedesk.service.AdmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/instructions")
public class InstructionController {
    private static final Logger log = LoggerFactory.getLogger(InstructionController.class);
    private final AdmissionService service;
    private final JdbcClient jdbc;
    public InstructionController(AdmissionService service, JdbcClient jdbc) {
        this.service = service;
        this.jdbc = jdbc;
    }
    public record View(UUID instructionId, String status, String reason, Instant receivedAt,
            Instant updatedAt, String statusUrl, String correlationId, String delivery,
            PaymentInstruction payload, String riskStatus, UUID assessmentId,
            java.util.List<String> riskReasonCodes, String traceId) {
        static View of(AdmissionService.Read read) {
            return of(read, null);
        }
        static View of(AdmissionService.Read read, String traceId) {
            var i = read.instruction();
            var risk = read.risk();
            return new View(i.instructionId(), i.state(), i.reason(), i.receivedAt(), i.updatedAt(),
                    i.statusUrl(), i.correlationId(), read.delivery(), i.payload(),
                    risk == null ? null : risk.policyResult(),
                    risk == null ? null : risk.assessmentId(),
                    risk == null ? null : risk.reasonCodes(), traceId);
        }
    }
    // Trace link for the detail view: the W3C traceparent stored on the
    // instruction's outbox row, reduced to its trace id. Additive and
    // nullable — the list path keeps traceId null (no N+1), and absent or
    // malformed parents yield no link, never a 500.
    static String parseTraceId(String traceparent) {
        if (traceparent == null) return null;
        var parts = traceparent.split("-");
        if (parts.length != 4 || !"00".equals(parts[0]) || parts[1].length() != 32) return null;
        return parts[1];
    }
    private String traceIdFor(UUID instructionId) {
        return parseTraceId(jdbc.sql("SELECT traceparent FROM outbox WHERE instruction_id = ? ORDER BY outbox_id ASC LIMIT 1")
                .param(instructionId).query(String.class).optional().orElse(null));
    }
    @PostMapping
    public ResponseEntity<View> submit(@RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation,
            @RequestBody PaymentInstruction payload) {
        var admission = service.admit(key, correlation, payload);
        // Re-read for the response: a repeat can return an instruction whose
        // delivery marker moved on since admission, and the view must say so.
        var id = admission.instruction().instructionId();
        var view = View.of(service.get(id), traceIdFor(id));
        // Identity joins the filter's admission scope: the stored correlation
        // (generated when the client sent none) plus the durable identity.
        LogContext.set("correlationId", view.correlationId());
        LogContext.set("instructionId", view.instructionId().toString());
        LogContext.set("runId", payload.runId());
        LogContext.set("outcome", admission.repeated() ? "repeated" : "accepted");
        log.info("admission.accepted");
        return ResponseEntity.status(admission.repeated() ? 200 : 202)
                .location(URI.create(view.statusUrl())).header("X-Correlation-Id", view.correlationId()).body(view);
    }
    @GetMapping("/{id}")
    public View get(@PathVariable UUID id) { return View.of(service.get(id), traceIdFor(id)); }

    // Audit timeline (mockup 03): the durable instruction_audit rows in commit
    // order — business transitions only, never transport retries. Unknown ids
    // 404 via the service read; the cap documents itself (transitions per
    // instruction are bounded by the state machine, cf. quarantine list(100)).
    public record AuditEvent(String oldState, String newState, String reason, String actor,
            Instant recordedAt) {}
    public record AuditView(UUID instructionId, List<AuditEvent> items) {}
    @GetMapping("/{id}/audit")
    public AuditView audit(@PathVariable UUID id) {
        service.get(id);
        var items = jdbc.sql("SELECT old_state, new_state, reason, actor, recorded_at"
                        + " FROM instruction_audit WHERE instruction_id = ? ORDER BY audit_id ASC LIMIT 100")
                .param(id).query((rs, row) -> new AuditEvent(rs.getString("old_state"),
                        rs.getString("new_state"), rs.getString("reason"), rs.getString("actor"),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant()))
                .list();
        return new AuditView(id, items);
    }

    // Attempt evidence (mockup 03 transport section): processing_attempt rows in
    // write order. Telemetry, never business truth — a rolled-back worker
    // attempt leaves no row here (V3 header). deliveryCount is the provider's
    // per-attempt count (V4), null when the worker never saw one.
    public record AttemptView(String eventId, String result, String reason, Integer deliveryCount,
            Instant startedAt, Instant endedAt) {}
    public record AttemptList(UUID instructionId, List<AttemptView> items) {}
    @GetMapping("/{id}/attempts")
    public AttemptList attempts(@PathVariable UUID id) {
        service.get(id);
        var items = jdbc.sql("SELECT event_id, result, reason, delivery_count, started_at, ended_at"
                        + " FROM processing_attempt WHERE instruction_id = ? ORDER BY attempt_id ASC LIMIT 100")
                .param(id).query((rs, row) -> new AttemptView(rs.getString("event_id"),
                        rs.getString("result"), rs.getString("reason"),
                        rs.getObject("delivery_count", Integer.class),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("ended_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("ended_at", OffsetDateTime.class).toInstant()))
                .list();
        return new AttemptList(id, items);
    }
    // Newest-first paging (mockup 02): order=desc returns the latest page
    // first with direction-scoped cursors; asc is the default and preserves
    // every existing caller.
    @GetMapping
    public ResponseEntity<List<View>> list(@RequestParam(required = false) String runId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Instant since,
            @RequestParam(required = false) Instant until,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "asc") String order) {
        if (!order.equals("asc") && !order.equals("desc"))
            throw new IllegalArgumentException("order must be asc|desc");
        var page = service.list(runId, status, limit, cursor, since, until, order.equals("desc"));
        var response = ResponseEntity.ok();
        if (page.nextCursor() != null) response.header("X-Next-Cursor", page.nextCursor());
        return response.body(page.items().stream().map(View::of).toList());
    }
}
