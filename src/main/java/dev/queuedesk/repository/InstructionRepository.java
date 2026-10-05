package dev.queuedesk.repository;

import dev.queuedesk.domain.StoredInstruction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// An interface is a runtime contract. Later, a Postgres adapter will replace the map.
public interface InstructionRepository {
    Optional<StoredInstruction> byKey(String key);
    Optional<StoredInstruction> byId(UUID id);
    List<StoredInstruction> all();
    void save(StoredInstruction instruction);
}
