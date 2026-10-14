package dev.queuedesk.risk;

import tools.jackson.databind.ObjectMapper;
import dev.queuedesk.domain.BusinessState;
import dev.queuedesk.domain.StoredInstruction;
import dev.queuedesk.messaging.InstructionReceivedEvent;
import dev.queuedesk.messaging.OutboxRepository;
import dev.queuedesk.repository.InstructionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;

/**
 * Audited human review of risk holds: the only way a held payment moves
 * again.
 *
 * <p>{@code RESUME} returns the payment from {@code RISK_HELD} to
 * {@code RECEIVED} and emits a new, deduplicated continuation event for the
 * <em>original</em> instruction through the outbox. The gate sees the
 * recorded RESUME review and lets that continuation fall through to normal
 * processing instead of re-holding it. {@code REJECT} ends the instruction.
 * Both decisions require a named actor and a written reason, recorded
 * before the state moves, in one transaction.
 *
 * <p>No review, timeout, or score ever creates a replacement instruction.
 * This build deliberately has no HTTP surface for reviews yet
 * ({@code POST /api/v1/risk/reviews} answers 404); the service exists so
 * the audited path is real, tested code the future operator UI will call.
 */
@Service
public class RiskReviewService {
    // Distinct outbox label for continuations; the payload stays the standard
    // InstructionReceived envelope so the worker path needs no new parser.
    public static final String CONTINUATION_EVENT_TYPE = "instruction.continuation.v1";

    private final RiskAssessmentRepository assessments;
    private final InstructionRepository instructions;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public RiskReviewService(RiskAssessmentRepository assessments, InstructionRepository instructions,
            OutboxRepository outbox, ObjectMapper mapper, PlatformTransactionManager txManager) {
        this.assessments = assessments;
        this.instructions = instructions;
        this.outbox = outbox;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Review outcome: the audit record's ID, the decision, and the continuation event when resumed. */
    public record ReviewResult(UUID reviewId, UUID instructionId, String decision, String continuationEventId) {}

    /**
     * Resumes a held instruction: records the audited RESUME, returns it to
     * {@code RECEIVED}, and enqueues its continuation event, atomically.
     *
     * @throws IllegalArgumentException when the instruction is unknown or actor/reason are invalid
     * @throws IllegalStateException when the instruction is not currently {@code RISK_HELD}
     */
    public ReviewResult resume(UUID instructionId, String actor, String reason) {
        return tx.execute(s -> {
            StoredInstruction instruction = instructions.byId(instructionId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown instructionId " + instructionId));
            if (!BusinessState.RISK_HELD.equals(instruction.state()))
                throw new IllegalStateException("instruction " + instructionId + " is not RISK_HELD");
            requireToken(actor, "actor");
            requireReason(reason);
            String continuationEventId = UUID.randomUUID().toString();
            UUID reviewId = UUID.randomUUID();
            assessments.saveReview(new RiskAssessmentRepository.Review(reviewId, instructionId,
                    actor, "RESUME", reason, continuationEventId, Instant.now()));
            if (!instructions.transition(instructionId, BusinessState.RISK_HELD, BusinessState.RECEIVED,
                    "RESUME:" + reason, actor))
                throw new IllegalStateException("instruction " + instructionId + " left RISK_HELD");
            outbox.insert(continuationEventId, instructionId, CONTINUATION_EVENT_TYPE,
                    InstructionReceivedEvent.of(continuationEventId, instruction, Instant.now()).toJson(mapper),
                    null, null);
            return new ReviewResult(reviewId, instructionId, "RESUME", continuationEventId);
        });
    }

    /**
     * Rejects a held instruction for good: records the audited REJECT and
     * moves it to the terminal {@code REJECTED} state, atomically.
     *
     * @throws IllegalArgumentException when the instruction is unknown or actor/reason are invalid
     * @throws IllegalStateException when the instruction is not currently {@code RISK_HELD}
     */
    public ReviewResult reject(UUID instructionId, String actor, String reason) {
        return tx.execute(s -> {
            StoredInstruction instruction = instructions.byId(instructionId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown instructionId " + instructionId));
            if (!BusinessState.RISK_HELD.equals(instruction.state()))
                throw new IllegalStateException("instruction " + instructionId + " is not RISK_HELD");
            requireToken(actor, "actor");
            requireReason(reason);
            UUID reviewId = UUID.randomUUID();
            assessments.saveReview(new RiskAssessmentRepository.Review(reviewId, instructionId,
                    actor, "REJECT", reason, null, Instant.now()));
            if (!instructions.transition(instructionId, BusinessState.RISK_HELD, BusinessState.REJECTED,
                    "REVIEW_REJECT:" + reason, actor))
                throw new IllegalStateException("instruction " + instructionId + " left RISK_HELD");
            return new ReviewResult(reviewId, instructionId, "REJECT", null);
        });
    }

    private static void requireToken(String value, String field) {
        if (value == null || !value.trim().matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException(field + " must be a 1..80 character synthetic token");
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new IllegalArgumentException("reason is required (1..500 characters)");
    }
}
