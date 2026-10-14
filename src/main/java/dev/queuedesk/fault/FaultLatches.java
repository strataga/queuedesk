package dev.queuedesk.fault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controlled crash windows for failure drills (FAIL-01).
 *
 * <p>Each latch is a one-shot switch placed exactly on a commit boundary:
 * the worker before and after its database commit, the publisher after its
 * MQ send, and the risk gate after saving an assessment. When the lab is
 * armed, tripping a latch halts the JVM with exit code 99. That is how
 * scenario SC08 kills the process at the right microsecond instead of
 * hoping a timed {@code kill} lands in the window.
 *
 * <p>Unit tests arm the same latches in throw mode ({@link Tripped}), which
 * proves latch placement and the recovery outcome without killing the test
 * JVM.
 *
 * <p>Safety: halt mode requires the explicit master switch
 * {@code queuedesk.fault.lab-armed} (environment variable
 * {@code QUEUEDESK_FAULT_LAB_ARMED}). The master switch and every latch
 * default to {@code false}, arming is logged loudly, and no latch is
 * reachable from any HTTP parameter. Only the process owner, who controls
 * the environment, can arm a crash.
 */
@Component
public class FaultLatches {
    /**
     * Thrown when a latch trips in throw mode. Extends {@link Error}
     * deliberately: the listener and publisher catch {@link RuntimeException}
     * for paced-redelivery bookkeeping, but a tripped latch must propagate
     * like a real crash. No FAILED evidence row, just death (halt) or
     * rollback (throw).
     */
    public static final class Tripped extends Error {
        public Tripped(String latch) { super("fault latch tripped: " + latch); }
    }
    public static final int HALT_EXIT_CODE = 99;
    private static final Logger log = LoggerFactory.getLogger(FaultLatches.class);
    private final boolean haltMode;
    private final AtomicBoolean workerBeforeCommit = new AtomicBoolean();
    private final AtomicBoolean workerAfterCommit = new AtomicBoolean();
    private final AtomicBoolean publisherAfterCommit = new AtomicBoolean();
    private final AtomicBoolean riskAfterAssessment = new AtomicBoolean();
    public FaultLatches(
            @Value("${queuedesk.fault.lab-armed:false}") boolean labArmed,
            @Value("${queuedesk.fault.worker-halt-before-commit:false}") boolean workerBefore,
            @Value("${queuedesk.fault.worker-halt-after-commit:false}") boolean workerAfter,
            @Value("${queuedesk.fault.publisher-halt-after-commit:false}") boolean publisherAfter,
            @Value("${queuedesk.fault.risk-halt-after-assessment:false}") boolean riskAfter) {
        this.haltMode = labArmed;
        workerBeforeCommit.set(labArmed && workerBefore);
        workerAfterCommit.set(labArmed && workerAfter);
        publisherAfterCommit.set(labArmed && publisherAfter);
        riskAfterAssessment.set(labArmed && riskAfter);
        if (labArmed && (workerBefore || workerAfter || publisherAfter || riskAfter))
            log.error("fault.armed workerBefore={} workerAfter={} publisherAfter={} riskAfter={} exitCode={} (LAB ONLY; process will halt, not degrade)",
                    workerBefore, workerAfter, publisherAfter, riskAfter, HALT_EXIT_CODE);
    }
    /** Crash point: the worker has done its work but not yet committed. No-op unless armed. */
    public void tripWorkerBeforeCommit() { trip(workerBeforeCommit, "worker-halt-before-commit"); }
    /** Crash point: the worker committed, but the MQ acknowledgement has not settled. No-op unless armed. */
    public void tripWorkerAfterCommit() { trip(workerAfterCommit, "worker-halt-after-commit"); }
    /** Crash point: the publisher sent to MQ but has not marked the outbox row published. No-op unless armed. */
    public void tripPublisherAfterCommit() { trip(publisherAfterCommit, "publisher-halt-after-commit"); }
    /**
     * Crash point for drill RS06: after the assessment row is saved, before
     * the hold outcome commits. A halt rolls everything back and redelivery
     * reassesses, so there is still exactly one outcome and never a
     * half-held instruction. No-op unless armed.
     */
    public void tripRiskAfterAssessment() { trip(riskAfterAssessment, "risk-halt-after-assessment"); }
    // Test seams: arm the same latch in throw mode. haltMode stays false in
    // tests because the environment is the only path to a real halt.
    /** Test seam: arm {@link #tripWorkerBeforeCommit} once, in throw mode. */
    public void armThrowWorkerBeforeCommitOnceForTest() { workerBeforeCommit.set(true); }
    /** Test seam: arm {@link #tripWorkerAfterCommit} once, in throw mode. */
    public void armThrowWorkerAfterCommitOnceForTest() { workerAfterCommit.set(true); }
    /** Test seam: arm {@link #tripPublisherAfterCommit} once, in throw mode. */
    public void armThrowPublisherAfterCommitOnceForTest() { publisherAfterCommit.set(true); }
    /** Test seam: arm {@link #tripRiskAfterAssessment} once, in throw mode. */
    public void armThrowRiskAfterAssessmentOnceForTest() { riskAfterAssessment.set(true); }
    /**
     * Disarms every latch. Tests call this from {@code @BeforeEach}: a latch
     * that never trips stays armed, and one test's unused arm must not leak
     * into the next test's commit path.
     */
    public void resetForTest() {
        workerBeforeCommit.set(false);
        workerAfterCommit.set(false);
        publisherAfterCommit.set(false);
        riskAfterAssessment.set(false);
    }
    private void trip(AtomicBoolean latch, String name) {
        if (!latch.compareAndSet(true, false)) return;
        log.error("fault.tripped name={} mode={}", name, haltMode ? "HALT" : "THROW");
        if (haltMode) Runtime.getRuntime().halt(HALT_EXIT_CODE);
        throw new Tripped(name);
    }
}
