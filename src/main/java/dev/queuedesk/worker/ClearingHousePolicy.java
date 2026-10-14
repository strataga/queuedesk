package dev.queuedesk.worker;

import dev.queuedesk.domain.PaymentInstruction;
import java.util.Optional;

/**
 * The mock clearing house: in-process, deterministic, no ledger.
 *
 * <p>It either accepts an instruction or rejects it with a stable business
 * reason code. A rejection here is an ordinary simulated outcome, committed
 * and acknowledged like any other. It is not an error and never causes a
 * redelivery.
 */
public interface ClearingHousePolicy {
    /**
     * Reviews one instruction before settlement.
     *
     * @return empty to accept, or the stable reason code to reject with
     */
    Optional<String> review(PaymentInstruction instruction);
}
