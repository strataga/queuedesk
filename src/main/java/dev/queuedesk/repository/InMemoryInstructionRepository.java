package dev.queuedesk.repository;

import dev.queuedesk.domain.StoredInstruction;
import java.util.*;

// Plain unit-test double: no Spring stereotype, constructed directly in tests.
// Single-JVM locks only; cross-process safety comes from the Postgres adapter.
public class InMemoryInstructionRepository implements InstructionRepository {
    private final Map<String, StoredInstruction> instructions = new LinkedHashMap<>();
    public synchronized Optional<StoredInstruction> byKey(String key) { return Optional.ofNullable(instructions.get(key)); }
    public synchronized Optional<StoredInstruction> byId(UUID id) {
        return instructions.values().stream().filter(i -> i.instructionId().equals(id)).findFirst();
    }
    public synchronized List<StoredInstruction> all() { return List.copyOf(instructions.values()); }
    @Override
    public synchronized List<StoredInstruction> recent(int limit) {
        return instructions.values().stream()
                .sorted(Comparator.comparing(StoredInstruction::receivedAt)
                        .thenComparing(StoredInstruction::instructionId).reversed())
                .limit(limit).toList();
    }
    public synchronized java.util.Map<String, Long> countByState(String runId) {
        return instructions.values().stream().filter(i -> i.payload().runId().equals(runId))
                .collect(java.util.stream.Collectors.groupingBy(StoredInstruction::state,
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()));
    }
    @Override
    public synchronized java.util.Map<String, Long> countLabByState(java.time.Instant since, java.time.Instant until) {
        return instructions.values().stream()
                .filter(i -> (since == null || !i.receivedAt().isBefore(since))
                        && (until == null || !i.receivedAt().isAfter(until)))
                .collect(java.util.stream.Collectors.groupingBy(StoredInstruction::state,
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()));
    }
    @Override
    public synchronized java.util.Map<java.time.Instant, Long> completionsPerBucket(
            java.time.Instant since, java.time.Instant until, long bucketSeconds) {
        return instructions.values().stream()
                .filter(i -> i.state().equals(
                        dev.queuedesk.domain.BusinessState.COMPLETED_SIMULATED))
                .filter(i -> (since == null || !i.updatedAt().isBefore(since))
                        && (until == null || !i.updatedAt().isAfter(until)))
                .collect(java.util.stream.Collectors.groupingBy(
                        i -> java.time.Instant.ofEpochSecond(
                                i.updatedAt().getEpochSecond() / bucketSeconds * bucketSeconds),
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()));
    }
    public synchronized void save(StoredInstruction instruction) {
        // Mirror the unique key: the JDBC adapter's bare INSERT throws
        // DuplicateKeyException on a clash, which the service's insert-race
        // path catches. Silently overwriting here would hide race bugs.
        var prev = instructions.putIfAbsent(instruction.idempotencyKey(), instruction);
        if (prev != null) throw new org.springframework.dao.DuplicateKeyException(
                "duplicate idempotency key: " + instruction.idempotencyKey());
    }
    public synchronized boolean transition(UUID id, String from, String to, String reason, String actor) {
        var current = byId(id);
        if (current.isEmpty() || !current.get().state().equals(from)) return false;
        var i = current.get();
        instructions.put(i.idempotencyKey(), new StoredInstruction(i.instructionId(), i.idempotencyKey(),
                i.correlationId(), i.payload(), i.receivedAt(), java.time.Instant.now(), to, reason, i.version() + 1));
        return true;
    }
    public synchronized List<StoredInstruction> list(String runId, String status, int limit, Optional<PageCursor> cursor,
            java.time.Instant since, java.time.Instant until, boolean newestFirst) {
        var base = Comparator.comparing(StoredInstruction::receivedAt)
                .thenComparing(StoredInstruction::instructionId);
        var ordered = instructions.values().stream()
                .filter(i -> runId == null || i.payload().runId().equals(runId))
                .filter(i -> status == null || i.state().equals(status))
                .filter(i -> since == null || !i.receivedAt().isBefore(since))
                .filter(i -> until == null || !i.receivedAt().isAfter(until))
                .sorted(newestFirst ? base.reversed() : base)
                .toList();
        int from = 0;
        if (cursor.isPresent()) {
            var c = cursor.get();
            while (from < ordered.size()
                    && (newestFirst ? !before(ordered.get(from), c) : !after(ordered.get(from), c))) from++;
        }
        return ordered.subList(from, Math.min(ordered.size(), from + limit + 1));
    }
    private static boolean after(StoredInstruction i, PageCursor c) {
        int byTime = i.receivedAt().compareTo(c.createdAt());
        return byTime > 0 || (byTime == 0 && i.instructionId().compareTo(c.instructionId()) > 0);
    }
    private static boolean before(StoredInstruction i, PageCursor c) {
        int byTime = i.receivedAt().compareTo(c.createdAt());
        return byTime < 0 || (byTime == 0 && i.instructionId().compareTo(c.instructionId()) < 0);
    }
}
