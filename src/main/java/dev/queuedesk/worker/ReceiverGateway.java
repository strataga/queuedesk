package dev.queuedesk.worker;

import java.util.Optional;
import java.util.UUID;

/**
 * The mock receiving bank, reached through a gateway so its failure modes
 * are explicit.
 *
 * <p>The receiver keeps one durable result per instruction ID. The first
 * call stores (or resumes) that single result; repeat calls return the
 * stored result instead of deciding again, and every call is counted as
 * evidence. Two honest failure modes exist:
 * <ul>
 *   <li>{@link ResponseLost}: the result was definitely stored, but the
 *       reply never arrived. A lookup will find the truth.</li>
 *   <li>{@link UncertainCall}: the call may or may not have applied.
 *       Reconcile before any reissue; never blindly retry.</li>
 * </ul>
 * No money movement is implied by any simulated outcome.
 */
public interface ReceiverGateway {
    /** A definitive receiver answer: {@code COMPLETED} or {@code REJECTED}, with a reason when rejected. */
    record CallResult(String outcome, String reason) {}
    /** The result is durably stored at the receiver, but this caller never saw the reply. */
    class ResponseLost extends RuntimeException {
        public ResponseLost(String ref) { super(ref); }
    }
    /** The call's effect is unknown. The only safe next step is a lookup, not a retry. */
    class UncertainCall extends RuntimeException {
        public UncertainCall(String ref) { super(ref); }
    }

    /**
     * Presents one instruction to the receiver. The first call stores (or
     * resumes) the single result; repeats return it unchanged.
     *
     * @throws ResponseLost when the result stored but the reply was lost
     * @throws UncertainCall when whether the call applied is genuinely unknown
     */
    CallResult receive(UUID instructionId, dev.queuedesk.domain.PaymentInstruction payload);

    /**
     * Reconciler reissue, called only after a lookup found no definitive
     * result: completes a pending marker, returns definitive rows, and
     * creates missing ones. Never loses a response. Empty means the
     * receiver genuinely does not know yet.
     */
    Optional<CallResult> reissue(UUID instructionId);
}
