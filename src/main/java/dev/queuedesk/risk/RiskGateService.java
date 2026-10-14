package dev.queuedesk.risk;

import tools.jackson.databind.ObjectMapper;
import dev.queuedesk.domain.BusinessState;
import dev.queuedesk.domain.StoredInstruction;
import dev.queuedesk.fault.FaultLatches;
import dev.queuedesk.messaging.InstructionReceivedEvent;
import dev.queuedesk.ops.QueuedeskMetrics;
import dev.queuedesk.ops.QueuedeskTracer;
import dev.queuedesk.repository.InstructionRepository;
import dev.queuedesk.worker.ProcessedMessageRepository;
import dev.queuedesk.worker.ProcessingAttemptRepository;
import dev.queuedesk.worker.ProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The pre-clearing risk gate: decides, before settlement, whether a payment
 * proceeds, is held for review, or is rejected.
 *
 * <p>Evaluation runs in three phases, and no database transaction is ever
 * held open across the provider call:
 * <ol>
 *   <li><b>Plan</b> (short transaction): load the instruction, freeze a
 *       feature snapshot, reuse an existing assessment when the versions
 *       match, and take a lease so only one evaluation calls the provider.</li>
 *   <li><b>Call</b> (no transaction): ask the signal provider under a bounded
 *       deadline. Rules-only mode skips the call entirely.</li>
 *   <li><b>Commit</b> (final transaction): recheck the dedup marker, save the
 *       assessment as durable evidence, then atomically hold, reject, or
 *       allow. ALLOW returns empty and the caller runs the unchanged
 *       processing path.</li>
 * </ol>
 *
 * <p>Failure always fails closed: a provider timeout or error maps to a
 * visible {@code NOT_ASSESSED} hold, never to an allow. The receiver is
 * never called for a held payment, and a hold resumes only through an
 * audited review ({@link RiskReviewService}), never through a replacement
 * instruction or a silent re-score.
 */
@Service
public class RiskGateService {
    private static final Logger log = LoggerFactory.getLogger(RiskGateService.class);
    private static final ExecutorService PROVIDER_CALLS = Executors.newCachedThreadPool(r -> {
        var thread = new Thread(r, "risk-provider-call");
        thread.setDaemon(true);
        return thread;
    });

    private final RiskProperties properties;
    private final FeatureExtractor features;
    private final RiskSignalProvider provider;
    private final RiskAssessmentRepository assessments;
    private final InstructionRepository instructions;
    private final ProcessedMessageRepository processed;
    private final ProcessingAttemptRepository attempts;
    private final QueuedeskMetrics metrics;
    private final QueuedeskTracer tracing;
    private final FaultLatches faults;
    private final ObjectMapper mapper;
    private final TransactionTemplate planTx;
    private final TransactionTemplate finalTx;

    public RiskGateService(RiskProperties properties, FeatureExtractor features, RiskSignalProvider provider,
            RiskAssessmentRepository assessments, InstructionRepository instructions,
            ProcessedMessageRepository processed, ProcessingAttemptRepository attempts,
            QueuedeskMetrics metrics, QueuedeskTracer tracing, FaultLatches faults,
            ObjectMapper mapper, PlatformTransactionManager txManager) {
        this.properties = properties;
        this.features = features;
        this.provider = provider;
        this.assessments = assessments;
        this.instructions = instructions;
        this.processed = processed;
        this.attempts = attempts;
        this.metrics = metrics;
        this.tracing = tracing;
        this.faults = faults;
        this.mapper = mapper;
        // Phase 1 is a SHORT write tx (it acquires the lease), closed before
        // the provider call — never held open during provider work (PRD §24).
        this.planTx = new TransactionTemplate(txManager);
        this.finalTx = new TransactionTemplate(txManager);
    }

    /** Returns the configured gate mode (status surfaces report it so an OFF lab explains itself). */
    public RiskMode mode() { return properties.mode(); }

    /**
     * Evaluates one delivered event.
     *
     * @return empty when the verdict is ALLOW (or the gate is OFF, or the
     *         normal path must decide), meaning the caller should run
     *         {@link ProcessingService#process} unchanged; otherwise the
     *         terminal hold/reject result, already committed
     */
    public java.util.Optional<ProcessingService.Result> evaluate(InstructionReceivedEvent envelope, int deliveryCount) {
        if (properties.mode() == RiskMode.OFF) return java.util.Optional.empty();
        final UUID instructionId;
        try {
            instructionId = UUID.fromString(envelope.instructionId());
        } catch (IllegalArgumentException | NullPointerException unparseable) {
            return java.util.Optional.empty();
        }
        // Phase 1 — short plan tx: anything the normal path must decide
        // (unknown instruction, wrong state, already-handled event) falls
        // through untouched so OFF-vs-ON semantics differ only at the gate.
        ReadPlan plan = planTx.execute(s -> plan(envelope, instructionId));
        if (plan == null || plan.verdict() == ReadPlan.Verdict.FALL_THROUGH) return java.util.Optional.empty();
        if (plan.verdict() == ReadPlan.Verdict.RESUMED) {
            log.info("risk.resumed instructionId={} (audited RESUME bypasses the hold)", instructionId);
            return java.util.Optional.empty();
        }
        // Phase 2 — provider call outside any tx, bounded by the deadline.
        ProviderOutcome providerOutcome = callProvider(plan);
        // Phase 3 — final tx: recheck the marker, then commit atomically. Any
        // failure releases this call's lease in a FRESH tx (the final tx is
        // doomed) so redelivery reassesses instead of wedging on a live row;
        // a crash between the two still heals via lease expiry.
        try {
            return finalTx.execute(s -> commit(plan, providerOutcome, envelope, deliveryCount));
        } catch (RuntimeException | Error failed) {
            releaseLeaseQuietly(instructionId);
            throw failed;
        }
    }

    private void releaseLeaseQuietly(UUID instructionId) {
        try {
            new TransactionTemplate(finalTx.getTransactionManager()).execute(s -> {
                assessments.completeLease(instructionId, properties.featureVersion(),
                        properties.questionVersion(), properties.policyVersion());
                return null;
            });
        } catch (RuntimeException cleanupLost) {
            log.warn("risk.lease.release.lost instructionId={} error={} (lease expiry still heals)",
                    instructionId, cleanupLost.toString());
        }
    }

    private record ReadPlan(Verdict verdict, StoredInstruction instruction, FeatureSnapshot snapshot,
            RiskAssessmentRepository.StoredAssessment reuse, String inputHash) {
        enum Verdict { FALL_THROUGH, EVALUATE, REUSE, RESUMED, CONFLICT_HOLD }
    }

    private ReadPlan plan(InstructionReceivedEvent envelope, UUID instructionId) {
        var instruction = instructions.byId(instructionId);
        if (instruction.isEmpty() || !BusinessState.RECEIVED.equals(instruction.get().state()))
            return new ReadPlan(ReadPlan.Verdict.FALL_THROUGH, null, null, null, null);
        if (processed.find(envelope.eventId()).isPresent())
            return new ReadPlan(ReadPlan.Verdict.FALL_THROUGH, null, null, null, null);
        var stored = instruction.get();
        Instant asOf = Instant.now();
        FeatureSnapshot snapshot = features.extract(stored, asOf);
        String inputHash = inputHash(stored, snapshot);
        var existing = assessments.find(instructionId, properties.featureVersion(),
                properties.questionVersion(), properties.policyVersion());
        // Audited RESUME after the assessment bypasses the hold: the review —
        // not a re-score — authorizes the continuation (no silent re-score).
        var review = assessments.latestReview(instructionId);
        if (review.isPresent() && "RESUME".equals(review.get().decision()) && existing.isPresent()
                && !review.get().recordedAt().isBefore(existing.get().assessedAt()))
            return new ReadPlan(ReadPlan.Verdict.RESUMED, stored, snapshot, existing.get(), inputHash);
        if (existing.isPresent())
            return new ReadPlan(ReadPlan.Verdict.REUSE, stored, snapshot, existing.get(), inputHash);
        // Fresh evaluation needs the call right. A live lease means another
        // call is in flight (or died holding one within its TTL): this
        // delivery skips the provider call and commits a visible NOT_ASSESSED
        // hold instead of burning retries toward the backout queue — a paced
        // transient here would route after three deliveries while the lease
        // (deadline + 30s) is still live, wedging the instruction RECEIVED.
        boolean leased = assessments.acquireLease(instructionId, properties.featureVersion(),
                properties.questionVersion(), properties.policyVersion(),
                Thread.currentThread().getName() + "-" + System.nanoTime(),
                asOf.plusMillis(properties.providerDeadlineMs() + 30_000));
        if (!leased)
            return new ReadPlan(ReadPlan.Verdict.CONFLICT_HOLD, stored, snapshot, null, inputHash);
        return new ReadPlan(ReadPlan.Verdict.EVALUATE, stored, snapshot, null, inputHash);
    }

    private record ProviderOutcome(RiskSignalProvider.Assessment assessment, String failureStatus) {
        static ProviderOutcome rulesOnly() { return new ProviderOutcome(null, null); }
        static ProviderOutcome failed(String status) { return new ProviderOutcome(null, status); }
    }

    private ProviderOutcome callProvider(ReadPlan plan) {
        // REUSE replays the stored verdict; CONFLICT_HOLD skips the call (the
        // lease says another evaluation owns it) and holds below instead.
        if (plan.verdict() == ReadPlan.Verdict.REUSE
                || plan.verdict() == ReadPlan.Verdict.CONFLICT_HOLD) return ProviderOutcome.rulesOnly();
        if (properties.mode() == RiskMode.RULES_ONLY) return ProviderOutcome.rulesOnly();
        try (var span = tracing.internal("queuedesk.risk.evaluate",
                Map.of("business.instruction.id", plan.instruction().instructionId().toString(),
                        "risk.mode", properties.mode().name()))) {
            Future<RiskSignalProvider.Assessment> call = PROVIDER_CALLS.submit(() -> {
                long start = System.nanoTime();
                try {
                    RiskSignalProvider.Assessment assessment = provider.assess(plan.snapshot(), plan.inputHash());
                    metrics.riskProviderCall(assessment.evaluationStatus(), assessment.latencyMs());
                    span.span().setAttribute("risk.evaluation.status", assessment.evaluationStatus());
                    return assessment;
                } catch (RiskSignalProvider.ProviderException failed) {
                    metrics.riskProviderCall(failed.evaluationStatus(),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                    span.span().setAttribute("risk.evaluation.status", failed.evaluationStatus());
                    throw new ProviderFailed(failed.evaluationStatus());
                }
            });
            try {
                return new ProviderOutcome(call.get(properties.providerDeadlineMs(), TimeUnit.MILLISECONDS), null);
            } catch (TimeoutException timedOut) {
                call.cancel(true);
                metrics.riskProviderCall("TIMEOUT", properties.providerDeadlineMs());
                span.span().setAttribute("risk.evaluation.status", "TIMEOUT");
                log.warn("risk.provider.timeout instructionId={}", plan.instruction().instructionId());
                return ProviderOutcome.failed("TIMEOUT");
            } catch (java.util.concurrent.ExecutionException failed) {
                if (failed.getCause() instanceof ProviderFailed fault) return ProviderOutcome.failed(fault.status());
                throw new IllegalStateException("risk provider call failed", failed);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted during risk evaluation", interrupted);
            }
        }
    }

    private static final class ProviderFailed extends RuntimeException {
        private final String status;
        ProviderFailed(String status) { super(status); this.status = status; }
        String status() { return status; }
    }

    // Phase 3: recheck the dedup marker (redelivery may have landed meanwhile),
    // then commit exactly one outcome atomically. Returns empty on ALLOW so the
    // caller runs the existing process path; the saved assessment is durable
    // evidence shared by both branches.
    private java.util.Optional<ProcessingService.Result> commit(ReadPlan plan, ProviderOutcome providerOutcome,
            InstructionReceivedEvent envelope, int deliveryCount) {
        var instruction = plan.instruction();
        UUID instructionId = instruction.instructionId();
        try {
            if (processed.find(envelope.eventId()).isPresent()) {
                assessments.completeLease(instructionId, properties.featureVersion(),
                        properties.questionVersion(), properties.policyVersion());
                return java.util.Optional.empty();
            }
            RiskPolicy.Decision decision;
            RiskSignalProvider.Assessment assessment = providerOutcome.assessment();
            Long providerLatency = null;
            String signalsJson = null;
            Long inputTokens = null;
            Long outputTokens = null;
            if (plan.verdict() == ReadPlan.Verdict.REUSE) {
                var stored = plan.reuse();
                decision = new RiskPolicy.Decision(stored.policyResult(), stored.reasonCodes(), stored.evaluationStatus());
            } else if (plan.verdict() == ReadPlan.Verdict.CONFLICT_HOLD) {
                // Another evaluation owns the call right: hold visibly as
                // NOT_ASSESSED/UNKNOWN without consulting the provider.
                decision = RiskPolicy.notAssessed("UNKNOWN");
            } else if (providerOutcome.failureStatus() != null) {
                decision = RiskPolicy.evaluateOnFault(plan.snapshot(), providerOutcome.failureStatus());
            } else {
                decision = RiskPolicy.evaluate(plan.snapshot(), assessment);
                if (assessment != null) {
                    providerLatency = assessment.latencyMs();
                    signalsJson = signalsJson(assessment);
                    if (assessment.usage() != null) {
                        inputTokens = assessment.usage().inputTokens();
                        outputTokens = assessment.usage().outputTokens();
                    }
                }
            }
            if (plan.verdict() == ReadPlan.Verdict.EVALUATE
                    || plan.verdict() == ReadPlan.Verdict.CONFLICT_HOLD) {
                persistAssessment(plan, decision, signalsJson, providerLatency, inputTokens, outputTokens);
                if (plan.verdict() == ReadPlan.Verdict.EVALUATE) {
                    // Crash window (RS06): assessment row saved, outcome not yet
                    // committed. A halt here rolls everything back; the reboot's
                    // redelivery finds the live lease and commits the single
                    // NOT_ASSESSED hold instead of reassessing.
                    faults.tripRiskAfterAssessment();
                }
            }
            return switch (decision.policyResult()) {
                case "ALLOW" -> {
                    assessments.completeLease(instructionId, properties.featureVersion(),
                            properties.questionVersion(), properties.policyVersion());
                    yield java.util.Optional.empty();
                }
                case "REVIEW", "NOT_ASSESSED" -> {
                    String reason = decision.policyResult()
                            + (decision.reasonCodes().isEmpty() ? "" : ":" + String.join(",", decision.reasonCodes()));
                    if (!instructions.transition(instructionId, BusinessState.RECEIVED,
                            BusinessState.RISK_HELD, reason, "risk-gate"))
                        throw new IllegalStateException("instruction " + instructionId + " left RECEIVED during risk evaluation");
                    processed.insert(envelope.eventId(), instructionId, "RISK_HELD:" + reason);
                    attempts.insert(instructionId, envelope.eventId(), "RISK_HELD", reason, deliveryCount);
                    metrics.riskHold(decision.policyResult());
                    assessments.completeLease(instructionId, properties.featureVersion(),
                            properties.questionVersion(), properties.policyVersion());
                    log.info("risk.held instructionId={} result={} reasons={}", instructionId,
                            decision.policyResult(), decision.reasonCodes());
                    yield java.util.Optional.of(new ProcessingService.Result("RISK_HELD",
                            BusinessState.RISK_HELD, reason));
                }
                case "REJECT" -> {
                    String reason = "RISK_POLICY:" + String.join(",", decision.reasonCodes());
                    if (!instructions.transition(instructionId, BusinessState.RECEIVED,
                            BusinessState.REJECTED, reason, "risk-gate"))
                        throw new IllegalStateException("instruction " + instructionId + " left RECEIVED during risk evaluation");
                    processed.insert(envelope.eventId(), instructionId, "REJECTED:" + reason);
                    attempts.insert(instructionId, envelope.eventId(), "BUSINESS_REJECT", reason, deliveryCount);
                    metrics.outcome(BusinessState.REJECTED, instruction.receivedAt());
                    assessments.completeLease(instructionId, properties.featureVersion(),
                            properties.questionVersion(), properties.policyVersion());
                    log.info("risk.rejected instructionId={} reasons={}", instructionId, decision.reasonCodes());
                    yield java.util.Optional.of(new ProcessingService.Result("BUSINESS_REJECT",
                            BusinessState.REJECTED, reason));
                }
                default -> throw new IllegalStateException("unknown policy result: " + decision.policyResult());
            };
        } catch (RuntimeException failed) {
            // The final tx rolls back; the lease must not pin the versions
            // forever. Best-effort delete in the same (doomed) tx is useless,
            // so callers rely on lease expiry + redelivery instead — except
            // DuplicateKey on the assessment identity, where the winner's row
            // stands and redelivery reuses it.
            if (failed instanceof DuplicateKeyException raced
                    && (plan.verdict() == ReadPlan.Verdict.EVALUATE
                        || plan.verdict() == ReadPlan.Verdict.CONFLICT_HOLD)) {
                throw new IllegalStateException("concurrent assessment won for " + instructionId
                        + "; redelivery reuses it (" + raced.getMessage() + ")");
            }
            throw failed;
        }
    }

    private void persistAssessment(ReadPlan plan, RiskPolicy.Decision decision, String signalsJson,
            Long providerLatency, Long inputTokens, Long outputTokens) {
        assessments.save(new RiskAssessmentRepository.StoredAssessment(UUID.randomUUID(),
                plan.instruction().instructionId(), properties.featureVersion(), properties.questionVersion(),
                properties.policyVersion(), plan.inputHash(), snapshotJson(plan.snapshot()),
                decision.evaluationStatus(), decision.policyResult(), decision.reasonCodes(),
                signalsJson, providerLatency, Instant.now(), inputTokens, outputTokens));
    }

    static String inputHash(StoredInstruction instruction, FeatureSnapshot snapshot) {
        String canonical = instruction.instructionId() + "|" + instruction.payload().amountMinor()
                + "|" + instruction.payload().senderParticipant() + "|" + instruction.payload().receiverParticipant()
                + "|" + snapshot.asOf() + "|" + snapshot.featureVersion();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String snapshotJson(FeatureSnapshot snapshot) {
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (Exception unserializable) {
            throw new IllegalStateException("feature snapshot is not JSON-serializable", unserializable);
        }
    }

    private String signalsJson(RiskSignalProvider.Assessment assessment) {
        try {
            var choices = assessment.choices().stream()
                    .map(c -> Map.of("questionId", c.questionId(), "choice", c.choice(),
                            "probabilities", c.probabilities(), "confidence", c.confidence()))
                    .toList();
            return mapper.writeValueAsString(Map.of(
                    "providerVersion", assessment.providerVersion(),
                    "modelVersion", assessment.modelVersion(),
                    "questionVersion", assessment.questionVersion(),
                    "choices", choices,
                    "nouls", assessment.noulQuestionIds(),
                    "latencyMs", assessment.latencyMs()));
        } catch (Exception unserializable) {
            throw new IllegalStateException("provider signals are not JSON-serializable", unserializable);
        }
    }
}
