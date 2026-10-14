package dev.queuedesk.worker;

import java.util.Optional;
import java.util.UUID;

// Mock receiving bank behind a gateway (REC-01): one durable result per
// instructionId, repeat calls return the stored result, calls counted. Two
// honest failure modes: ResponseLost (definitely stored, reply lost) and
// UncertainCall (may or may not have applied — reconcile before reissue).
// No money movement is implied by any simulated outcome.
public interface ReceiverGateway {
    record CallResult(String outcome, String reason) {}
    class ResponseLost extends RuntimeException {
        public ResponseLost(String ref) { super(ref); }
    }
    class UncertainCall extends RuntimeException {
        public UncertainCall(String ref) { super(ref); }
    }
    // First call stores (or resumes) the single result; repeats return it.
    CallResult receive(UUID instructionId, dev.queuedesk.domain.PaymentInstruction payload);
    // Reconciler reissue after lookup: completes PENDING, returns definitive
    // rows, creates missing ones. Never loses a response; empty means the
    // receiver genuinely does not know yet.
    Optional<CallResult> reissue(UUID instructionId);
}
