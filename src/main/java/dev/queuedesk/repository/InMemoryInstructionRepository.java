package dev.queuedesk.repository;

import dev.queuedesk.domain.StoredInstruction;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class InMemoryInstructionRepository implements InstructionRepository {
    private final Map<String, StoredInstruction> instructions = new LinkedHashMap<>();
    public synchronized Optional<StoredInstruction> byKey(String key) { return Optional.ofNullable(instructions.get(key)); }
    public synchronized Optional<StoredInstruction> byId(UUID id) {
        return instructions.values().stream().filter(i -> i.instructionId().equals(id)).findFirst();
    }
    public synchronized List<StoredInstruction> all() { return List.copyOf(instructions.values()); }
    public synchronized void save(StoredInstruction instruction) { instructions.put(instruction.idempotencyKey(), instruction); }
}
