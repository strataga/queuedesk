package dev.queuedesk.risk;

import java.time.Instant;

/**
 * A frozen feature snapshot: everything the risk gate is allowed to look at
 * for one payment, computed once and stored with the assessment.
 *
 * <p>Missing data is explicit, never defaulted: {@code missingElapsed} and
 * {@code missingBaseline} say so out loud, and the baseline ratio uses the
 * {@link #NO_BASELINE} sentinel instead of NaN or a quiet 1.0. Velocity
 * counts unique instructions only. Retries and transport duplicates never
 * create instruction rows, so counting rows excludes them by construction.
 * Plain Java with constructor-checked invariants, unit-testable without
 * Spring.
 */
public record FeatureSnapshot(
        long amountMinor,
        int priorUniqueCount,
        Long elapsedSincePreviousMs,
        boolean receiverSeenBefore,
        double amountToBaselineRatio,
        Instant asOf,
        String featureVersion,
        boolean missingElapsed,
        boolean missingBaseline) {

    // Baseline ratio sentinel: -1 means "no baseline" (missingBaseline true).
    // Never NaN, never a silently defaulted 1.0.
    public static final double NO_BASELINE = -1.0;

    public FeatureSnapshot {
        if (amountMinor < 1) throw new IllegalArgumentException("amountMinor must be >= 1");
        if (priorUniqueCount < 0) throw new IllegalArgumentException("priorUniqueCount must be >= 0");
        if (asOf == null) throw new IllegalArgumentException("asOf is required");
        if (featureVersion == null || featureVersion.isBlank())
            throw new IllegalArgumentException("featureVersion is required");
        if (missingElapsed && elapsedSincePreviousMs != null)
            throw new IllegalArgumentException("missingElapsed requires a null elapsed value");
        if (!missingElapsed && elapsedSincePreviousMs == null)
            throw new IllegalArgumentException("elapsedSincePreviousMs is required unless missingElapsed");
        if (missingBaseline && amountToBaselineRatio != NO_BASELINE)
            throw new IllegalArgumentException("missingBaseline requires the NO_BASELINE sentinel");
        if (!missingBaseline && !(amountToBaselineRatio > 0) && !Double.isInfinite(amountToBaselineRatio))
            throw new IllegalArgumentException("amountToBaselineRatio must be positive or infinite");
    }
}
