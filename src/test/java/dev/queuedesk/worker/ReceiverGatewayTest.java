package dev.queuedesk.worker;

import dev.queuedesk.domain.PaymentInstruction;
import dev.queuedesk.domain.StoredInstruction;
import dev.queuedesk.service.AdmissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Receiver truth is independent and idempotent per instruction: repeats
 * return the stored result, fixtures lose replies deterministically,
 * unknown stays unknown across reissue, and concurrent first calls yield
 * one row. Integration suite on real Postgres with no MQ traffic.
 */
@Tag("integration")
@SpringBootTest(properties = {"queuedesk.publisher.enabled=false", "queuedesk.worker.enabled=false",
        "queuedesk.reconcile.enabled=false", "queuedesk.quarantine.enabled=false"})
class ReceiverGatewayTest {
    @Autowired ReceiverGateway gateway;
    @Autowired ReceiverResultRepository results;
    @Autowired AdmissionService service;
    @Autowired JdbcClient jdbc;
    private String run;
    @BeforeEach void freshRun() { run = "gw-" + UUID.randomUUID(); }
    @AfterEach void cleanup() {
        jdbc.sql("DELETE FROM receiver_result WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM instruction_audit WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM outbox WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM instruction WHERE run_id = ?").param(run).update();
    }
    private StoredInstruction admit(String key, String ref) {
        return service.admit(key, "corr-" + key,
                new PaymentInstruction(ref, run, "SENDER_A", "RECEIVER_B", 100L, "USD")).instruction();
    }
    @Test void normalCallCompletesAndRepeatsReturnTheSameResult() {
        var saved = admit(run + "-n0", "ref-0");
        var first = gateway.receive(saved.instructionId(), saved.payload());
        assertEquals("COMPLETED", first.outcome());
        var second = gateway.receive(saved.instructionId(), saved.payload());
        assertEquals("COMPLETED", second.outcome());
        var row = results.find(saved.instructionId()).orElseThrow();
        assertEquals(2, row.calls());
    }
    @Test void uncertainCallStoresThenLosesTheReply() {
        var saved = admit(run + "-u0", "uncertain-0");
        var lost = assertThrows(ReceiverGateway.ResponseLost.class,
                () -> gateway.receive(saved.instructionId(), saved.payload()));
        assertTrue(lost.getMessage().contains("COMPLETED"));
        assertEquals("COMPLETED", results.find(saved.instructionId()).orElseThrow().outcome());
        // The reissued call succeeds: loss is a first-call phenomenon.
        var reissued = gateway.reissue(saved.instructionId()).orElseThrow();
        assertEquals("COMPLETED", reissued.outcome());
        assertEquals(2, results.find(saved.instructionId()).orElseThrow().calls());
    }
    @Test void uncertainRejectionStoresRejectThenLosesTheReply() {
        var saved = admit(run + "-ur0", "uncertain-reject-0");
        assertThrows(ReceiverGateway.ResponseLost.class,
                () -> gateway.receive(saved.instructionId(), saved.payload()));
        var row = results.find(saved.instructionId()).orElseThrow();
        assertEquals("REJECTED", row.outcome());
        assertEquals("FIXTURE_RECEIVER_REJECT", row.reason());
        assertEquals("REJECTED", gateway.reissue(saved.instructionId()).orElseThrow().outcome());
    }
    @Test void unknownStaysUnknownAcrossReissue() {
        var saved = admit(run + "-k0", "unknown-0");
        assertThrows(ReceiverGateway.UncertainCall.class,
                () -> gateway.receive(saved.instructionId(), saved.payload()));
        assertEquals("UNKNOWN", results.find(saved.instructionId()).orElseThrow().outcome());
        assertTrue(gateway.reissue(saved.instructionId()).isEmpty());
        assertEquals("UNKNOWN", results.find(saved.instructionId()).orElseThrow().outcome());
        assertEquals(2, results.find(saved.instructionId()).orElseThrow().calls());
    }
    @Test void flakyResumesPendingOnRepeatOrReissue() {
        var repeat = admit(run + "-f0", "flaky-0");
        assertThrows(ReceiverGateway.UncertainCall.class,
                () -> gateway.receive(repeat.instructionId(), repeat.payload()));
        assertEquals("PENDING", results.find(repeat.instructionId()).orElseThrow().outcome());
        // A worker redelivery repeats the call: it resumes to definitive.
        assertEquals("COMPLETED", gateway.receive(repeat.instructionId(), repeat.payload()).outcome());
        var reissued = admit(run + "-f1", "flaky-1");
        assertThrows(ReceiverGateway.UncertainCall.class,
                () -> gateway.receive(reissued.instructionId(), reissued.payload()));
        assertEquals("COMPLETED", gateway.reissue(reissued.instructionId()).orElseThrow().outcome());
    }
    @Test void concurrentFirstCallsYieldOneRow() throws Exception {
        var saved = admit(run + "-r0", "ref-race");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<String>>();
            for (int i = 0; i < 16; i++)
                tasks.add(() -> gateway.receive(saved.instructionId(), saved.payload()).outcome());
            for (var future : pool.invokeAll(tasks)) assertEquals("COMPLETED", future.get());
        }
        assertEquals("COMPLETED", results.find(saved.instructionId()).orElseThrow().outcome());
    }
    @Test void nullPayloadIsRejectedBeforeAnyWrite() {
        var saved = admit(run + "-n0", "ref-null");
        assertThrows(IllegalArgumentException.class,
                () -> gateway.receive(saved.instructionId(), null));
        assertTrue(results.find(saved.instructionId()).isEmpty());
    }
    @Test void pendingRepeatFollowsStoredRefNotCallerRef() {
        var saved = admit(run + "-p0", "flaky-0");
        assertThrows(ReceiverGateway.UncertainCall.class,
                () -> gateway.receive(saved.instructionId(), saved.payload()));
        // Divergent repeat payload: the stored flaky-0 ref (not the caller's
        // reject ref) decides the resumed outcome.
        var divergent = new PaymentInstruction("uncertain-reject-zzz", run, "SENDER_A", "RECEIVER_B", 100L, "USD");
        assertEquals("COMPLETED", gateway.receive(saved.instructionId(), divergent).outcome());
    }
    @Test void reissueCreatesMissingResultWithoutLoss() {
        var saved = admit(run + "-m0", "ref-0");
        // No call yet (simulated pre-store crash): lookup-then-reissue creates.
        assertTrue(results.find(saved.instructionId()).isEmpty());
        assertEquals("COMPLETED", gateway.reissue(saved.instructionId()).orElseThrow().outcome());
        assertEquals(1, results.find(saved.instructionId()).orElseThrow().calls());
    }
}
