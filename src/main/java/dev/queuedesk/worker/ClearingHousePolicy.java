package dev.queuedesk.worker;

import dev.queuedesk.domain.PaymentInstruction;
import java.util.Optional;

// Mock clearing house (WORK-02): in-process, deterministic, no ledger. Accepts
// the instruction or rejects it with a stable business reason; a rejection is
// an ordinary simulated outcome, committed and acknowledged normally.
public interface ClearingHousePolicy {
    // Empty means accept; present means reject with that stable reason code.
    Optional<String> review(PaymentInstruction instruction);
}
