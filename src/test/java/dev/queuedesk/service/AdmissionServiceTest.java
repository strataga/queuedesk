package dev.queuedesk.service;

import tools.jackson.databind.ObjectMapper;
import dev.queuedesk.domain.PaymentInstruction;
import dev.queuedesk.messaging.InMemoryOutboxRepository;
import dev.queuedesk.repository.InMemoryInstructionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;

// Fast single-threaded coverage on the in-memory double. True concurrency proof
// lives in PostgresAdmissionTest: only the database constraint survives races.
class AdmissionServiceTest {
    private PaymentInstruction payload() { return new PaymentInstruction("ref","run","SENDER_A","RECEIVER_B",1L,"USD"); }
    // No-op manager: runs the same template code with no real transaction.
    private PlatformTransactionManager direct() {
        return new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
            @Override protected void doCommit(DefaultTransactionStatus status) {}
            @Override protected void doRollback(DefaultTransactionStatus status) {}
        };
    }
    private AdmissionService service() {
        return new AdmissionService(new InMemoryInstructionRepository(), new InMemoryOutboxRepository(),
                new dev.queuedesk.worker.InMemoryQuarantineRepository(),
                new dev.queuedesk.worker.InMemoryProcessingAttemptRepository(),
                new dev.queuedesk.risk.InMemoryRiskAssessmentRepository(), direct(), new ObjectMapper());
    }
    @Test void repeatReturnsOriginalAndConflictPreservesIt() {
        var service = service();
        var first = service.admit("same", "correlation", payload()).instruction();
        var repeat = service.admit("same", "other-correlation", payload());
        assertTrue(repeat.repeated());
        assertEquals(first.instructionId(), repeat.instruction().instructionId());
        assertEquals("correlation", repeat.instruction().correlationId());
        var changed = new PaymentInstruction("ref","run","SENDER_A","RECEIVER_B",2L,"USD");
        assertThrows(AdmissionService.Conflict.class, () -> service.admit("same", "c", changed));
        assertEquals(1L, service.get(first.instructionId()).instruction().payload().amountMinor());
    }
    @Test void doubleSaveThrowsLikeTheUniqueConstraint() {
        // Parity with JdbcInstructionRepository: a bare INSERT violates the
        // unique key, so the double must throw DuplicateKeyException (which
        // the service's insert-race path catches) instead of overwriting.
        var repo = new InMemoryInstructionRepository();
        var stored = service().admit("dup", null, payload()).instruction();
        var clash = new dev.queuedesk.domain.StoredInstruction(java.util.UUID.randomUUID(), "dup",
                "other", stored.payload(), stored.receivedAt(), stored.receivedAt(), "RECEIVED", null, 0);
        repo.save(stored);
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> repo.save(clash));
        assertEquals(stored.instructionId(), repo.byKey("dup").orElseThrow().instructionId());
    }
    @Test void freshRepositoryLosesAdmissionsAndCapacityIsBounded() {
        var service = service();
        var saved = service.admit("first",null,payload()).instruction();
        assertThrows(AdmissionService.Missing.class, () -> service().get(saved.instructionId()));
        for(int i=1;i<1000;i++) service.admit("key-"+i,null,payload());
        assertThrows(AdmissionService.Capacity.class, () -> service.admit("overflow",null,payload()));
        assertTrue(service.admit("first",null,payload()).repeated());
    }
    @Test void listNewestFirstReversesAndPages() {
        var service = service();
        service.admit("n1", null, payload());
        service.admit("n2", null, payload());
        service.admit("n3", null, payload());
        var ascIds = service.list(null, null, 10, null, null, null, false).items().stream()
                .map(r -> r.instruction().instructionId()).toList();
        var descIds = service.list(null, null, 10, null, null, null, true).items().stream()
                .map(r -> r.instruction().instructionId()).toList();
        assertEquals(ascIds.reversed(), descIds);
        var first = service.list(null, null, 2, null, null, null, true);
        assertEquals(2, first.items().size());
        assertNotNull(first.nextCursor());
        var second = service.list(null, null, 2, first.nextCursor(), null, null, true);
        assertEquals(1, second.items().size());
        assertNull(second.nextCursor());
        var paged = new java.util.ArrayList<>(first.items());
        paged.addAll(second.items());
        assertEquals(descIds, paged.stream().map(r -> r.instruction().instructionId()).toList());
    }
}
