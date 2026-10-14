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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The reconciliation identity is scoped to OPEN tasks: a second task while
 * one is open is rejected, but after DONE a re-uncertain instruction may
 * open a fresh task instead of crashing on its own history.
 * Integration suite.
 */
@Tag("integration")
@SpringBootTest(properties = {"queuedesk.publisher.enabled=false", "queuedesk.worker.enabled=false",
        "queuedesk.reconcile.enabled=false", "queuedesk.quarantine.enabled=false"})
class ReconciliationTaskRescopeTest {
    @Autowired ReconciliationTaskRepository tasks;
    @Autowired AdmissionService service;
    @Autowired JdbcClient jdbc;
    private String run;
    @BeforeEach void freshRun() { run = "rescope-" + UUID.randomUUID(); }
    @AfterEach void cleanup() {
        jdbc.sql("DELETE FROM reconciliation_task WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM instruction_audit WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM outbox WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM instruction WHERE run_id = ?").param(run).update();
    }
    @Test void openScopedIdentityRejectsDoubleOpenButAllowsTaskAfterDone() {
        StoredInstruction saved = service.admit(run + "-k", "corr-" + run,
                new PaymentInstruction("ref-0", run, "SENDER_A", "RECEIVER_B", 100L, "USD")).instruction();
        tasks.create(saved.instructionId(), "e1", null, null);
        assertThrows(DuplicateKeyException.class,
                () -> tasks.create(saved.instructionId(), "e2", null, null));
        long openId = tasks.findOpen(saved.instructionId()).orElseThrow().taskId();
        tasks.complete(openId, null);
        assertTrue(tasks.findOpen(saved.instructionId()).isEmpty());
        tasks.create(saved.instructionId(), "e3", null, null);
        assertEquals("e3", tasks.findOpen(saved.instructionId()).orElseThrow().eventId());
    }
}
