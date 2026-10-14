package dev.queuedesk.http;

import dev.queuedesk.worker.QuarantineRepository;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only view of the quarantine projection: poison messages held on the
 * backout queue, with the reason each one is held.
 *
 * <p>The response carries the reconciler heartbeat. When the heartbeat is
 * older than 60 seconds (four missed 15-second passes), {@code stale} is
 * {@code true}: the view says "I may be out of date" instead of pretending
 * to be current or empty. Observing quarantine never authorizes replay;
 * there is deliberately no write endpoint here.
 */
@RestController
@RequestMapping("/api/v1/quarantine")
public class QuarantineController {
    /** One held (quarantined) event and why it is held. */
    public record HeldItem(String eventId, UUID instructionId, String reason, Instant firstSeenAt, Instant lastSeenAt) {}
    /** Quarantine response: freshness evidence plus the held items (capped at 100). */
    public record QuarantineView(Instant lastReconciledAt, boolean stale, String lastError, List<HeldItem> items) {}
    private final QuarantineRepository quarantine;
    public QuarantineController(QuarantineRepository quarantine) { this.quarantine = quarantine; }
    @GetMapping
    public QuarantineView view() {
        var heartbeat = quarantine.readHeartbeat();
        boolean stale = heartbeat.lastReconciledAt() == null
                || Duration.between(heartbeat.lastReconciledAt(), Instant.now()).abs().toSeconds() > 60;
        var items = quarantine.list(100).stream()
                .map(i -> new HeldItem(i.eventId(), i.instructionId(), i.reason(), i.firstSeenAt(), i.lastSeenAt()))
                .toList();
        return new QuarantineView(heartbeat.lastReconciledAt(), stale, heartbeat.lastError(), items);
    }
}
