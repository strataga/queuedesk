package dev.queuedesk.worker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory {@link ProcessingAttemptRepository} for unit tests. No Spring
 * stereotype; tests construct it directly and assert on the recorded
 * attempt sequence.
 */
public class InMemoryProcessingAttemptRepository implements ProcessingAttemptRepository {
    private record Row(UUID instructionId, String eventId, String result, String reason) {}
    private final List<Row> rows = new ArrayList<>();
    @Override
    public synchronized void insert(UUID instructionId, String eventId, String result, String reason, Integer deliveryCount) {
        rows.add(new Row(instructionId, eventId, result, reason));
    }
    @Override
    public synchronized List<Attempt> forInstruction(UUID instructionId) {
        return rows.stream().filter(r -> instructionId.equals(r.instructionId()))
                .map(r -> new Attempt(r.eventId(), r.result(), r.reason())).toList();
    }
    @Override
    public synchronized long countResult(UUID instructionId, String result) {
        return rows.stream().filter(r -> instructionId.equals(r.instructionId()) && result.equals(r.result())).count();
    }
    @Override
    public synchronized Set<UUID> withFailures(java.util.Collection<UUID> instructionIds) {
        var wanted = new HashSet<>(instructionIds);
        var done = new HashSet<UUID>();
        for (var r : rows)
            if (wanted.contains(r.instructionId()) && (r.result().equals("FAILED") || r.result().equals("UNSUPPORTED")))
                done.add(r.instructionId());
        return done;
    }
    @Override
    public synchronized Optional<EventEvidence> latestForEvent(String eventId) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            var r = rows.get(i);
            if (eventId.equals(r.eventId())) return Optional.of(new EventEvidence(r.instructionId(), r.reason()));
        }
        return Optional.empty();
    }
}
