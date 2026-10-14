package dev.queuedesk.http;

import dev.queuedesk.worker.QuarantineRepository;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Read-only quarantine projection (QUAR-02): held events with reasons plus the
// reconciler heartbeat. Stale means the heartbeat aged past 60s (four missed
// 15s passes) — the view says so instead of masquerading as current or empty.
// Observing never authorizes replay (deferred B01).
@RestController
@RequestMapping("/api/v1/quarantine")
public class QuarantineController {
    public record HeldItem(String eventId, UUID instructionId, String reason, Instant firstSeenAt, Instant lastSeenAt) {}
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
