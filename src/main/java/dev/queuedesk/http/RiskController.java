package dev.queuedesk.http;

import tools.jackson.databind.ObjectMapper;
import dev.queuedesk.ops.SpanRingBuffer;
import dev.queuedesk.repository.PageCursor;
import dev.queuedesk.risk.JevProperties;
import dev.queuedesk.risk.RiskAssessmentRepository;
import dev.queuedesk.risk.RiskGateService;
import dev.queuedesk.risk.RiskPolicy;
import dev.queuedesk.risk.RiskProperties;
import dev.queuedesk.risk.RiskSignalProvider;
import dev.queuedesk.service.AdmissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only risk panel: the held-payments list, one assessment in full
 * detail, and a summary with the Jev spending ledger.
 *
 * <p>Review <em>actions</em> (approve or reject a hold) are deliberately
 * deferred. The reserved {@code POST /reviews} route answers 404 in the
 * standard error shape until the authority design for acting on holds
 * lands. Everything here only explains decisions the gate already made.
 *
 * <p>Wording is part of the contract: assessment output explains a hold, it
 * is not a fraud determination, and provider confidence is confidence in an
 * answer to an exact question, never a probability of fraud.
 */
@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {
    private static final Set<String> KNOWN_RESULTS = Set.of("ALLOW", "REVIEW", "NOT_ASSESSED", "REJECT");
    private static final String HOLD_NOTE =
            "Assessment output explains the hold. It is not a fraud determination.";
    private static final String CONFIDENCE_NOTE =
            "Confidence is in the answer to the exact question. It is never the probability of fraud.";

    private final RiskAssessmentRepository assessments;
    private final AdmissionService service;
    private final RiskGateService gate;
    private final SpanRingBuffer ring;
    private final ObjectMapper mapper;
    private final RiskProperties properties;

    public RiskController(RiskAssessmentRepository assessments, AdmissionService service,
            RiskGateService gate, SpanRingBuffer ring, ObjectMapper mapper, RiskProperties properties) {
        this.assessments = assessments;
        this.service = service;
        this.gate = gate;
        this.ring = ring;
        this.mapper = mapper;
        this.properties = properties;
    }

    /**
     * Metered-usage ledger: durable token totals, the list-price cost
     * estimate, the configured budget cap, and whether the cap is exhausted.
     * All zeros unless live Jev calls happened; only Jev rows carry usage.
     */
    public record UsageView(long calls, long inputTokens, long outputTokens, double costUsd,
            double budgetUsd, boolean exhausted) {}
    /** Risk summary response: current mode, hold counters, and the usage ledger. */
    public record Summary(Instant asOf, String mode, long riskHeld, long reviewRequired,
            long notAssessed, String note, UsageView usage) {}

    /** Returns hold counters for the current gate mode plus Jev budget status. */
    @GetMapping("/summary")
    public Summary summary(@RequestParam(defaultValue = "15") int windowMinutes) {
        window(windowMinutes);
        var totals = assessments.usageTotals();
        long spentNano = JevProperties.costNanoUsd(totals.inputTokens());
        long budgetNano = properties.jevBudgetNanoUsd();
        var usage = new UsageView(totals.calls(), totals.inputTokens(), totals.outputTokens(),
                spentNano / 1_000_000_000.0, properties.jevBudgetUsd(), spentNano >= budgetNano);
        return new Summary(Instant.now(), gate.mode().name(),
                assessments.countByState("RISK_HELD"),
                assessments.countByResult("REVIEW"),
                assessments.countByResult("NOT_ASSESSED"), HOLD_NOTE, usage);
    }

    /** One row of the held-payments list: the payment, its business outcome, and why the gate held it. */
    public record HeldItem(UUID instructionId, String clientReference, long amountMinor, String currency,
            String businessOutcome, String riskOutput, List<String> reasonCodes) {}
    /** Held-list response, carrying the gate mode so an empty OFF-mode list explains itself. */
    public record HeldList(List<HeldItem> items, String mode) {}

    /**
     * Lists assessed payments, newest first, with optional filters for
     * verdict ({@code assessment}), reason code, and a free-text query
     * ({@code q}). Unknown filter values are 400s, never silent empty lists.
     * Paging uses the same opaque cursor contract as the instruction list.
     */
    @GetMapping("/assessments")
    public ResponseEntity<HeldList> list(
            @RequestParam(required = false) String assessment,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        Set<String> results = Set.of();
        if (assessment != null) {
            if (!KNOWN_RESULTS.contains(assessment))
                throw new IllegalArgumentException("unknown assessment filter: " + assessment);
            results = Set.of(assessment);
        }
        if (reason != null && !RiskPolicy.KNOWN_CODES.contains(reason))
            throw new IllegalArgumentException("unknown reason filter: " + reason);
        if (q != null && (q.isBlank() || q.length() > 80))
            throw new IllegalArgumentException("q must be 1..80 characters");
        var decoded = PageCursor.decode(cursor);
        var rows = assessments.listHeld(results, reason, q, limit, decoded);
        boolean more = rows.size() > limit;
        var page = (more ? rows.subList(0, limit) : rows).stream()
                .map(r -> new HeldItem(r.instructionId(), r.clientReference(), r.amountMinor(), r.currency(),
                        r.businessOutcome(), r.riskOutput(), r.reasonCodes()))
                .toList();
        var response = ResponseEntity.ok();
        if (more) {
            var last = rows.get(limit - 1);
            response.header("X-Next-Cursor", PageCursor.encode(last.assessedAt(), last.instructionId()));
        }
        return response.body(new HeldList(page, gate.mode().name()));
    }

    /** One rule that fired, with the feature evidence that tripped it. */
    public record MatchedRule(String code, String detail) {}
    /** One provider answer (or non-answer) to a fixed yes/no question. */
    public record SignalView(String questionId, String question, String type, Object answer,
            Double confidence, String confidenceNote) {}
    /** One timed stage of the assessment, for the detail timeline. */
    public record StageView(String stage, Long durationMs) {}
    /** The exact versions that produced an assessment, so old decisions stay interpretable. */
    public record VersionsView(String featureSchema, String rules, String policy, String questions) {}
    /** Full assessment detail: features seen, rules matched, provider signals, and timing. */
    public record Detail(UUID instructionId, String clientReference, String participants, long amountMinor,
            String currency, String businessOutcome, String riskOutput, String riskOutputText,
            Map<String, Object> features, List<MatchedRule> matchedRules, String policy,
            List<SignalView> signals, List<StageView> timeline, Long totalMs, Instant assessedAt,
            String traceId, VersionsView versions) {}

    /**
     * Returns the latest assessment for one instruction in full. Answers 404
     * when the instruction is unknown or was never assessed (for example
     * with the gate OFF).
     */
    @GetMapping("/assessments/{instructionId}")
    public Detail detail(@PathVariable UUID instructionId) {
        AdmissionService.Read read = service.get(instructionId);
        var assessment = assessments.latestByInstruction(instructionId)
                .orElseThrow(AdmissionService.Missing::new);
        var payload = read.instruction().payload();
        Map<String, Object> features = parseJson(assessment.featureSnapshotJson());
        var matched = assessment.reasonCodes().stream()
                .map(code -> new MatchedRule(code, detailFor(code, features)))
                .toList();
        var signals = parseSignals(assessment.signalsJson());
        List<StageView> timeline = assessment.signalsJson() == null
                ? List.of()
                : List.of(new StageView("mock_provider", assessment.providerLatencyMs()));
        return new Detail(instructionId, payload.clientReference(),
                payload.senderParticipant() + " → " + payload.receiverParticipant(),
                payload.amountMinor(), payload.currency(), read.instruction().state(),
                assessment.policyResult(), outputText(assessment.policyResult()), features, matched,
                "Any matched rule → REVIEW", signals, timeline, assessment.providerLatencyMs(),
                assessment.assessedAt(), traceIdFor(instructionId),
                new VersionsView(assessment.featureVersion(), RiskPolicy.RULES_VERSION,
                        assessment.policyVersion(), assessment.questionVersion()));
    }

    /**
     * Reserved review-action contract, deferred by design. The route exists
     * so clients get the stable {@link ApiErrors} shape instead of the
     * container default. It records nothing and changes nothing.
     */
    @PostMapping("/reviews")
    public ResponseEntity<ApiErrors.Error> deferredReview(@RequestBody(required = false) String body) {
        return ResponseEntity.status(404).body(new ApiErrors.Error("NOT_FOUND",
                "Review actions are deferred to B01; the risk panel is read-only"));
    }

    private static void window(int windowMinutes) {
        if (windowMinutes < 1 || windowMinutes > 60)
            throw new IllegalArgumentException("windowMinutes must be 1..60");
    }

    private static String outputText(String result) {
        return switch (result) {
            case "REVIEW" -> "Review required";
            case "NOT_ASSESSED" -> "Not assessed";
            case "ALLOW" -> "Allowed";
            case "REJECT" -> "Rejected";
            default -> result;
        };
    }

    private String detailFor(String code, Map<String, Object> features) {
        if (RiskPolicy.NEW_RECIPIENT.equals(code)) return "recipient_seen_before = false";
        if (RiskPolicy.UNUSUAL_AMOUNT.equals(code))
            return "amount_to_baseline_ratio = " + features.getOrDefault("amountToBaselineRatio", "?")
                    + " >= " + RiskPolicy.UNUSUAL_RATIO;
        if (RiskPolicy.SIMULATED_POLICY_BLOCK.equals(code))
            return "amount_minor >= " + RiskPolicy.SIMULATED_BLOCK_AMOUNT_MINOR
                    + " with new recipient (simulation policy)";
        return code;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception unreadable) {
            throw new IllegalStateException("stored feature snapshot is not valid JSON", unreadable);
        }
    }

    @SuppressWarnings("unchecked")
    private List<SignalView> parseSignals(String signalsJson) {
        if (signalsJson == null) return List.of();
        try {
            Map<String, Object> root = mapper.readValue(signalsJson, Map.class);
            var out = new java.util.ArrayList<SignalView>();
            for (var raw : (List<Map<String, Object>>) root.getOrDefault("choices", List.of())) {
                String questionId = (String) raw.get("questionId");
                out.add(new SignalView(questionId, RiskSignalProvider.questionText(questionId), "boolean",
                        Boolean.valueOf((String) raw.get("choice")),
                        ((Number) raw.get("confidence")).doubleValue(), CONFIDENCE_NOTE));
            }
            for (var noul : (List<String>) root.getOrDefault("nouls", List.of())) {
                out.add(new SignalView(noul, RiskSignalProvider.questionText(noul), "boolean",
                        null, null, CONFIDENCE_NOTE));
            }
            return List.copyOf(out);
        } catch (Exception unreadable) {
            throw new IllegalStateException("stored signals are not valid JSON", unreadable);
        }
    }

    // Sampled diagnostics, not durable truth: null once the ring evicts.
    private String traceIdFor(UUID instructionId) {
        return ring.spans().stream()
                .filter(s -> instructionId.toString().equals(
                        s.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("business.instruction.id"))))
                .map(s -> s.getSpanContext().getTraceId())
                .findFirst().orElse(null);
    }
}
