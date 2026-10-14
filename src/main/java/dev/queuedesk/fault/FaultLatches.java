package dev.queuedesk.fault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;

// Controlled crash windows (FAIL-01): one-shot latches that fire exactly at a
// commit boundary — worker before/after its DB commit, publisher after its MQ
// send. Firing HALTS the JVM (exit 99) when lab-armed, which is how SC08 kills
// the process at the right microsecond instead of hoping. Unit-test seams arm
// the same latch in THROW mode (Tripped), proving latch placement and the
// recovery outcome without killing the test JVM.
//
// Safety: halt mode requires the explicit master switch
// `queuedesk.fault.lab-armed` (env QUEUEDESK_FAULT_LAB_ARMED); all latches and
// the master default to false, arming is logged loudly, and no latch is
// reachable from any HTTP parameter — only the process owner can set env.
@Component
public class FaultLatches {
    // Extends Error deliberately: listener/publisher catch RuntimeException for
    // paced redelivery bookkeeping, but a tripped latch must propagate like a
    // real crash — no FAILED evidence row, just death (halt) or rollback (throw).
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
    public void tripWorkerBeforeCommit() { trip(workerBeforeCommit, "worker-halt-before-commit"); }
    public void tripWorkerAfterCommit() { trip(workerAfterCommit, "worker-halt-after-commit"); }
    public void tripPublisherAfterCommit() { trip(publisherAfterCommit, "publisher-halt-after-commit"); }
    // RS06 crash window: after the assessment row is saved, before the hold
    // outcome commits. A halt rolls everything back; redelivery reassesses —
    // still exactly one outcome, never a half-held instruction.
    public void tripRiskAfterAssessment() { trip(riskAfterAssessment, "risk-halt-after-assessment"); }
    // Test seams: arm the same latch in throw mode (haltMode stays false in
    // tests — env is the only path to a real halt).
    public void armThrowWorkerBeforeCommitOnceForTest() { workerBeforeCommit.set(true); }
    public void armThrowWorkerAfterCommitOnceForTest() { workerAfterCommit.set(true); }
    public void armThrowPublisherAfterCommitOnceForTest() { publisherAfterCommit.set(true); }
    public void armThrowRiskAfterAssessmentOnceForTest() { riskAfterAssessment.set(true); }
    // A latch that never trips stays armed: call from @BeforeEach so one
    // test's unused arm cannot leak into the next test's commit path.
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
