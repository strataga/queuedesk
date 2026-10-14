package dev.queuedesk.ops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Laboratory safeguards: one read-only view over the lab's stop conditions.
 *
 * <p>The generator consults this endpoint before sending and halts new
 * traffic when the outbox backlog exceeds its limit or free disk drops
 * below the reserve. These are safeguards, not capacity claims. Pending
 * work is never cleared to satisfy a guard; the operator drains the lab or
 * shuts it down explicitly (see the runbooks).
 */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {
    private final JdbcClient jdbc;
    private final long backlogLimit;
    private final long diskReserveBytes;
    public OpsController(JdbcClient jdbc,
            @Value("${queuedesk.guards.backlog-limit:1000}") long backlogLimit,
            @Value("${queuedesk.guards.disk-reserve-bytes:10737418240}") long diskReserveBytes) {
        this.jdbc = jdbc;
        this.backlogLimit = backlogLimit;
        this.diskReserveBytes = diskReserveBytes;
    }
    /** Guard status: both checks, the combined halt decision, and human-readable reasons. */
    public record Guards(long outboxPending, long backlogLimit, boolean backlogHalt,
            long diskFreeBytes, long diskReserveBytes, boolean diskHalt,
            boolean haltTraffic, List<String> reasons) {}

    /** Returns current guard readings. {@code haltTraffic} is true when any guard trips. */
    @GetMapping("/guards")
    public Guards guards() throws Exception {
        long pending = jdbc.sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
        long free = java.nio.file.Files.getFileStore(java.nio.file.Path.of(".").toAbsolutePath()).getUsableSpace();
        boolean backlogHalt = pending > backlogLimit;
        boolean diskHalt = free < diskReserveBytes;
        var reasons = new ArrayList<String>();
        if (backlogHalt) reasons.add("outbox backlog " + pending + " exceeds limit " + backlogLimit);
        if (diskHalt) reasons.add("free disk " + free + " below reserve " + diskReserveBytes);
        return new Guards(pending, backlogLimit, backlogHalt, free, diskReserveBytes, diskHalt,
                backlogHalt || diskHalt, List.copyOf(reasons));
    }
}
