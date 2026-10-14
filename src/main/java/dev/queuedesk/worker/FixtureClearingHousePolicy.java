package dev.queuedesk.worker;

import dev.queuedesk.domain.PaymentInstruction;
import org.springframework.stereotype.Component;
import java.util.Optional;

/**
 * Fixture-driven clearing policy: deterministic on the payload so scenario
 * tests and the generator can demand a rejection by construction. A payment
 * whose {@code clientReference} starts with {@code reject-} is rejected;
 * everything else is accepted.
 */
@Component
public class FixtureClearingHousePolicy implements ClearingHousePolicy {
    /** clientReference prefix that forces a clearing rejection. */
    public static final String REJECT_PREFIX = "reject-";
    /** The stable reason code returned for fixture rejections. */
    public static final String FIXTURE_REJECT = "FIXTURE_POLICY_REJECT";
    @Override
    public Optional<String> review(PaymentInstruction instruction) {
        if (instruction.clientReference() != null && instruction.clientReference().startsWith(REJECT_PREFIX))
            return Optional.of(FIXTURE_REJECT);
        return Optional.empty();
    }
}
