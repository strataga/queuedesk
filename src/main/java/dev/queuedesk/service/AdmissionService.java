package dev.queuedesk.service;

import dev.queuedesk.domain.*;
import dev.queuedesk.repository.InstructionRepository;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class AdmissionService {
    private final InstructionRepository repository;
    // Constructor injection: Spring passes the repository, like explicit dependency wiring in Node.
    public AdmissionService(InstructionRepository repository) { this.repository = repository; }
    public record Admission(StoredInstruction instruction, boolean repeated) {}
    public static class Conflict extends RuntimeException {}
    public static class Capacity extends RuntimeException {}
    public static class Missing extends RuntimeException {}

    // One process lock makes check-and-save atomic ONLY inside this starter JVM.
    // Postgres must replace this with a transaction and a unique key constraint.
    public synchronized Admission admit(String key, String correlation, PaymentInstruction payload) {
        key = PaymentInstruction.token(key, "Idempotency-Key");
        correlation = correlation == null ? UUID.randomUUID().toString()
                : PaymentInstruction.token(correlation, "X-Correlation-Id");
        var previous = repository.byKey(key);
        if (previous.isPresent()) {
            if (!previous.get().payload().equals(payload)) throw new Conflict();
            return new Admission(previous.get(), true);
        }
        if (repository.all().size() >= 1000) throw new Capacity();
        var instruction = new StoredInstruction(UUID.randomUUID(), key, correlation, payload, Instant.now());
        repository.save(instruction);
        return new Admission(instruction, false);
    }
    public StoredInstruction get(UUID id) { return repository.byId(id).orElseThrow(Missing::new); }
    public List<StoredInstruction> list(String runId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        return repository.all().stream().filter(i -> runId == null || i.payload().runId().equals(runId)).limit(limit).toList();
    }
}
