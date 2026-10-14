package dev.queuedesk.service;

import dev.queuedesk.domain.PaymentInstruction;
import dev.queuedesk.domain.RequestHash;
import dev.queuedesk.domain.StoredInstruction;
import dev.queuedesk.repository.InstructionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

// Durability proofs against real local Postgres (runbook: scripts/postgres up first).
// Each test uses a unique runId and deletes its rows afterwards.
// No background schedulers: cached test contexts must not run pollers that
// steal fellow suites' work (WorkerTest's tasks were once skipped mid-test
// by a stale scheduled poller holding the claim lock).
// OPS-02: integration suite — needs the named lab PG + MQ; CI runs it explicitly.
@Tag("integration")
@SpringBootTest(properties = {"queuedesk.publisher.enabled=false", "queuedesk.worker.enabled=false",
        "queuedesk.reconcile.enabled=false", "queuedesk.quarantine.enabled=false"})
class PostgresAdmissionTest {
    @Autowired AdmissionService service;
    @Autowired InstructionRepository repository;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;
    private String run;
    @BeforeEach void freshRun() { run = "pg-" + UUID.randomUUID(); }
    @AfterEach void cleanup() {
        jdbc.sql("DELETE FROM instruction_audit WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM outbox WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ?)").param(run).update();
        jdbc.sql("DELETE FROM instruction WHERE run_id = ?").param(run).update();
    }
    private PaymentInstruction payload(String ref, long amount) {
        return new PaymentInstruction(ref, run, "SENDER_A", "RECEIVER_B", amount, "USD");
    }
    private long countByKey(String key) {
        return jdbc.sql("SELECT COUNT(*) FROM instruction WHERE idempotency_key = ?").param(key).query(Long.class).single();
    }
    @Test void concurrentSameKeyYieldsOneIdentity() throws Exception {
        String key = run + "-race";
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<AdmissionService.Admission>>();
            for (int i = 0; i < 20; i++) tasks.add(() -> service.admit(key, null, payload("ref", 7L)));
            var ids = new HashSet<UUID>();
            int first = 0;
            for (var future : pool.invokeAll(tasks)) {
                var admission = future.get();
                ids.add(admission.instruction().instructionId());
                if (!admission.repeated()) first++;
            }
            assertEquals(1, ids.size());
            assertEquals(1, first);
        }
        assertEquals(1, countByKey(key));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM instruction_audit WHERE instruction_id = (SELECT instruction_id FROM instruction WHERE idempotency_key = ?)").param(key).query(Long.class).single());
    }
    @Test void capacityIsExactUnderConcurrency() throws Exception {
        // The advisory lock serializes check-and-insert: racing distinct-key
        // admits against a nearly full store must land exactly on 1000.
        long before = jdbc.sql("SELECT COUNT(*) FROM instruction").query(Long.class).single();
        int headroom = 5, racers = 20;
        int fill = (int) (1000 - before - headroom);
        assertTrue(fill > racers, "lab too dirty for the capacity race: " + before);
        jdbc.sql("""
                INSERT INTO instruction (instruction_id, idempotency_key, request_hash, run_id,
                  client_reference, sender_participant, receiver_participant, amount_minor,
                  currency, state, correlation_id, version)
                SELECT gen_random_uuid(), 'capfill-' || g, 'x', ?, 'cap',
                  'SENDER_A', 'RECEIVER_B', 1, 'USD', 'RECEIVED', 'cap', 0
                FROM generate_series(1, ?) g""").params(run, fill).update();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < racers; i++) {
                String key = run + "-cap-" + i;
                tasks.add(() -> {
                    try { service.admit(key, null, payload("ref", 1L)); return true; }
                    catch (AdmissionService.Capacity e) { return false; }
                });
            }
            int admitted = 0;
            for (var future : pool.invokeAll(tasks)) if (future.get()) admitted++;
            assertEquals(headroom, admitted);
            assertEquals(1000, jdbc.sql("SELECT COUNT(*) FROM instruction").query(Long.class).single());
        } finally {
            jdbc.sql("DELETE FROM outbox WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ? OR idempotency_key LIKE 'capfill-%')").param(run).update();
            jdbc.sql("DELETE FROM instruction_audit WHERE instruction_id IN (SELECT instruction_id FROM instruction WHERE run_id = ? OR idempotency_key LIKE 'capfill-%')").param(run).update();
            jdbc.sql("DELETE FROM instruction WHERE run_id = ? OR idempotency_key LIKE 'capfill-%'").param(run).update();
        }
    }
    @Test void changedBodyRacePreservesOriginal() throws Exception {
        String key = run + "-conflict";
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<String>>();
            for (int i = 0; i < 10; i++) {
                long amount = i + 1;
                tasks.add(() -> {
                    try { service.admit(key, null, payload("ref", amount)); return "admitted"; }
                    catch (AdmissionService.Conflict e) { return "conflict"; }
                });
            }
            var outcomes = new HashSet<String>();
            for (var future : pool.invokeAll(tasks)) outcomes.add(future.get());
            assertTrue(outcomes.contains("admitted"));
            assertTrue(outcomes.contains("conflict"));
        }
        assertEquals(1, countByKey(key));
    }
    @Test void admissionAndAuditCommitAtomically() {
        var template = new TransactionTemplate(txManager);
        String key = run + "-atomic";
        var first = new StoredInstruction(UUID.randomUUID(), key, "corr", payload("ref", 3L), Instant.now(), Instant.now(), "RECEIVED", null, 0);
        var second = new StoredInstruction(UUID.randomUUID(), key, "corr", payload("ref", 3L), Instant.now(), Instant.now(), "RECEIVED", null, 0);
        assertThrows(DuplicateKeyException.class, () -> template.<Void>execute(s -> {
            repository.save(first);
            repository.save(second);
            return null;
        }));
        assertEquals(0, countByKey(key));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM instruction_audit WHERE instruction_id = ?").param(first.instructionId()).query(Long.class).single());
    }
    @Test void storedHashMatchesSemanticFingerprint() {
        var body = payload("ref", 9L);
        var id = service.admit(run + "-hash", null, body).instruction().instructionId();
        String stored = jdbc.sql("SELECT request_hash FROM instruction WHERE instruction_id = ?").param(id).query(String.class).single();
        assertEquals(RequestHash.of(body), stored);
    }
    @Test void admissionStoresReceivedStateWithAudit() {
        var saved = service.admit(run + "-state", "corr-1", payload("ref", 5L)).instruction();
        assertEquals("RECEIVED", saved.state());
        assertEquals(0, saved.version());
        var reread = service.get(saved.instructionId());
        assertEquals("RECEIVED", reread.instruction().state());
        assertEquals("corr-1", reread.instruction().correlationId());
        assertEquals("PENDING_PUBLISH", reread.delivery());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM instruction_audit WHERE instruction_id = ? AND new_state = 'RECEIVED'").param(saved.instructionId()).query(Long.class).single());
    }
}
