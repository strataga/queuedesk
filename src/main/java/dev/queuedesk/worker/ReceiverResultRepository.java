package dev.queuedesk.worker;

import java.util.Optional;
import java.util.UUID;

// One durable receiver row per instructionId (REC-01).
public interface ReceiverResultRepository {
    record Row(String outcome, String reason, int calls) {}
    Optional<Row> find(UUID instructionId);
    void insert(UUID instructionId, String outcome, String reason);
    void complete(UUID instructionId, String outcome, String reason);
    void touch(UUID instructionId);
}
