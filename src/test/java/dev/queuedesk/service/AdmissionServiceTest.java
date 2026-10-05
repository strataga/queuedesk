package dev.queuedesk.service;

import dev.queuedesk.domain.PaymentInstruction;
import dev.queuedesk.repository.InMemoryInstructionRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AdmissionServiceTest {
    private PaymentInstruction payload() { return new PaymentInstruction("ref","run","SENDER_A","RECEIVER_B",1L,"USD"); }
    @Test void concurrentRepeatsCreateOneInstruction() throws Exception {
        var repository = new InMemoryInstructionRepository();
        var service = new AdmissionService(repository);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<AdmissionService.Admission>>();
            for (int i=0;i<20;i++) tasks.add(() -> service.admit("same","correlation",payload()));
            var results = pool.invokeAll(tasks);
            var ids = new HashSet<UUID>();
            int first = 0;
            for (var result : results) { var admission=result.get(); ids.add(admission.instruction().instructionId()); if(!admission.repeated()) first++; }
            assertEquals(1, ids.size()); assertEquals(1, first); assertEquals(1, repository.all().size());
        }
    }
    @Test void freshRepositoryLosesAdmissionsAndCapacityIsBounded() {
        var service = new AdmissionService(new InMemoryInstructionRepository());
        var saved = service.admit("first",null,payload()).instruction();
        assertThrows(AdmissionService.Missing.class, () -> new AdmissionService(new InMemoryInstructionRepository()).get(saved.instructionId()));
        for(int i=1;i<1000;i++) service.admit("key-"+i,null,payload());
        assertThrows(AdmissionService.Capacity.class, () -> service.admit("overflow",null,payload()));
        assertTrue(service.admit("first",null,payload()).repeated());
    }
}
