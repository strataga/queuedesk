package dev.queuedesk.worker;

import dev.queuedesk.domain.PaymentInstruction;
import dev.queuedesk.ops.QueuedeskTracer;
import dev.queuedesk.repository.InstructionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Optional;
import java.util.UUID;

/**
 * Fixture-driven mock receiver: behavior is deterministic on the payment's
 * {@code clientReference}, so scenario tests can demand uncertainty by
 * construction instead of by timing luck.
 *
 * <p>Reference prefixes select the drill: {@code uncertain-} stores a
 * result then loses the reply, {@code uncertain-reject-} does the same with
 * a rejection, {@code unknown-} never knows, and {@code flaky-} crashes the
 * first call after storing a pending marker. Everything else completes.
 *
 * <p>Every call runs in its own transaction ({@code REQUIRES_NEW}):
 * receiver truth commits independently of, and survives rollback of, the
 * worker's result transaction. That independence is the point: it is what
 * makes reconciliation from stored receiver truth possible.
 */
@Component
public class FixtureReceiverGateway implements ReceiverGateway {
    /** References with this prefix store a completion, then lose the reply. */
    public static final String UNCERTAIN_PREFIX = "uncertain-";
    /** References with this prefix store a rejection, then lose the reply. Checked before {@link #UNCERTAIN_PREFIX}. */
    public static final String UNCERTAIN_REJECT_PREFIX = "uncertain-reject-";
    /** References with this prefix are stored as permanently unknown. */
    public static final String UNKNOWN_PREFIX = "unknown-";
    /** References with this prefix crash the first call, leaving a resumable pending marker. */
    public static final String FLAKY_PREFIX = "flaky-";
    private final ReceiverResultRepository results;
    private final InstructionRepository instructions;
    private final QueuedeskTracer tracing;
    private final TransactionTemplate ownTx;
    public FixtureReceiverGateway(ReceiverResultRepository results, InstructionRepository instructions,
            QueuedeskTracer tracing, PlatformTransactionManager txManager) {
        this.results = results;
        this.instructions = instructions;
        this.tracing = tracing;
        this.ownTx = new TransactionTemplate(txManager);
        this.ownTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override
    public CallResult receive(UUID instructionId, PaymentInstruction payload) {
        if (payload == null || payload.clientReference() == null)
            throw new IllegalArgumentException("fixture receiver requires a payload with clientReference");
        // The store commits inside; loss/uncertainty throws OUTSIDE, after the
        // commit — throwing inside would roll the stored truth back.
        try (var span = tracing.client("queuedesk.receiver.call",
                java.util.Map.of("business.instruction.id", instructionId.toString()))) {
            Stored stored;
            try {
                stored = ownTx.execute(s -> receiveInTransaction(instructionId, payload));
            } catch (org.springframework.dao.DuplicateKeyException raced) {
                // Lost the first-call race: both sides saw no row and raced
                // the insert. The loser's transaction rolled back; retry once
                // in a fresh one — the winner's row now exists, so the retry
                // takes the repeat branch and cannot race again.
                stored = ownTx.execute(s -> receiveInTransaction(instructionId, payload));
            }
            span.event("receiver." + stored.outcome().toLowerCase(java.util.Locale.ROOT));
            if (stored.lost()) throw new ResponseLost(stored.detail());
            if (stored.uncertain()) throw new UncertainCall(stored.detail());
            return new CallResult(stored.outcome(), stored.reason());
        }
    }
    @Override
    public Optional<CallResult> reissue(UUID instructionId) {
        try (var span = tracing.client("queuedesk.receiver.reissue",
                java.util.Map.of("business.instruction.id", instructionId.toString()))) {
            Optional<CallResult> result;
            try {
                result = ownTx.execute(s -> reissueInTransaction(instructionId));
            } catch (org.springframework.dao.DuplicateKeyException raced) {
                // Same first-call race as receive(): retry once in a fresh
                // transaction — the winner's row now takes the repeat branch.
                result = ownTx.execute(s -> reissueInTransaction(instructionId));
            }
            result.ifPresent(call -> span.event("receiver." + call.outcome().toLowerCase(java.util.Locale.ROOT)));
            return result;
        }
    }
    private record Stored(String outcome, String reason, boolean lost, boolean uncertain, String detail) {}
    private Stored receiveInTransaction(UUID instructionId, PaymentInstruction payload) {
        var existing = results.find(instructionId);
        // Repeat call: the stored result stands; only the call count moves.
        if (existing.isPresent()) return repeatResult(instructionId, payload, existing.get());
        String ref = payload.clientReference();
        if (ref.startsWith(UNKNOWN_PREFIX)) {
            results.insert(instructionId, "UNKNOWN", "FIXTURE_RECEIVER_UNKNOWN");
            return new Stored("UNKNOWN", "FIXTURE_RECEIVER_UNKNOWN", false, true,
                    "receiver stored UNKNOWN for " + instructionId);
        }
        if (ref.startsWith(FLAKY_PREFIX)) {
            results.insert(instructionId, "PENDING", "FIXTURE_FIRST_CALL_CRASH");
            return new Stored("PENDING", "FIXTURE_FIRST_CALL_CRASH", false, true,
                    "receiver call crashed for " + instructionId + " (PENDING marker stored)");
        }
        boolean reject = ref.startsWith(UNCERTAIN_REJECT_PREFIX);
        String outcome = reject ? "REJECTED" : "COMPLETED";
        String reason = reject ? "FIXTURE_RECEIVER_REJECT" : null;
        results.insert(instructionId, outcome, reason);
        if (ref.startsWith(UNCERTAIN_PREFIX))
            return new Stored(outcome, reason, true, false,
                    "receiver stored " + outcome + " but lost the reply for " + instructionId);
        return new Stored(outcome, reason, false, false, null);
    }
    private Stored repeatResult(UUID instructionId, PaymentInstruction payload, ReceiverResultRepository.Row row) {
        return switch (row.outcome()) {
            // A repeat of an unresolved marker resumes it deterministically: the
            // reissued call succeeds (response loss is a first-call phenomenon).
            // The ref comes from the STORED instruction, never the caller: a
            // divergent repeat payload must not change the recorded outcome.
            case "PENDING" -> {
                var ref = instructions.byId(instructionId).map(i -> i.payload().clientReference())
                        .orElse(payload.clientReference());
                var completed = completeFromFixture(instructionId, ref);
                String reason = completed.equals("REJECTED") ? "FIXTURE_RECEIVER_REJECT" : null;
                yield new Stored(completed, reason, false, false, null);
            }
            case "UNKNOWN" -> {
                results.touch(instructionId);
                yield new Stored("UNKNOWN", row.reason(), false, true,
                        "receiver still UNKNOWN for " + instructionId);
            }
            default -> {
                results.touch(instructionId);
                yield new Stored(row.outcome(), row.reason(), false, false, null);
            }
        };
    }
    private Optional<CallResult> reissueInTransaction(UUID instructionId) {
        var payload = instructions.byId(instructionId)
                .orElseThrow(() -> new IllegalArgumentException("unknown instruction " + instructionId)).payload();
        var existing = results.find(instructionId);
        if (existing.isEmpty()) {
            // Lookup found nothing, so creating now is a safe reissue (D-05):
            // the reissued call succeeds without response loss.
            String ref = payload.clientReference();
            if (ref.startsWith(UNKNOWN_PREFIX)) {
                results.insert(instructionId, "UNKNOWN", "FIXTURE_RECEIVER_UNKNOWN");
                return Optional.empty();
            }
            String outcome = ref.startsWith(UNCERTAIN_REJECT_PREFIX) ? "REJECTED" : "COMPLETED";
            String reason = outcome.equals("REJECTED") ? "FIXTURE_RECEIVER_REJECT" : null;
            results.insert(instructionId, outcome, reason);
            return Optional.of(new CallResult(outcome, reason));
        }
        return switch (existing.get().outcome()) {
            case "PENDING" -> {
                var completed = completeFromFixture(instructionId, payload.clientReference());
                yield Optional.of(new CallResult(completed, completed.equals("REJECTED") ? "FIXTURE_RECEIVER_REJECT" : null));
            }
            case "UNKNOWN" -> { results.touch(instructionId); yield Optional.empty(); }
            default -> {
                results.touch(instructionId);
                yield Optional.of(new CallResult(existing.get().outcome(), existing.get().reason()));
            }
        };
    }
    private String completeFromFixture(UUID instructionId, String ref) {
        String outcome = ref.startsWith(UNCERTAIN_REJECT_PREFIX) ? "REJECTED" : "COMPLETED";
        String reason = outcome.equals("REJECTED") ? "FIXTURE_RECEIVER_REJECT" : null;
        results.complete(instructionId, outcome, reason);
        return outcome;
    }
}
