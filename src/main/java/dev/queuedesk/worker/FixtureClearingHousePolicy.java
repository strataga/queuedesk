package dev.queuedesk.worker;

import dev.queuedesk.domain.PaymentInstruction;
import org.springframework.stereotype.Component;
import java.util.Optional;

// Fixture-driven mock clearing: deterministic on the payload so scenario tests
// (and the Phase 4 generator) can demand a rejection by construction. Anything
// not matching a reject fixture is accepted.
@Component
public class FixtureClearingHousePolicy implements ClearingHousePolicy {
    public static final String REJECT_PREFIX = "reject-";
    public static final String FIXTURE_REJECT = "FIXTURE_POLICY_REJECT";
    @Override
    public Optional<String> review(PaymentInstruction instruction) {
        if (instruction.clientReference() != null && instruction.clientReference().startsWith(REJECT_PREFIX))
            return Optional.of(FIXTURE_REJECT);
        return Optional.empty();
    }
}
