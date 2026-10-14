package dev.queuedesk.ops;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Gauge fallback without Spring or a database: live values cache, outages
// serve last-good, and a gauge that never read serves -1 (unknown, not zero).
class QueuedeskMetricsTest {
    private QueuedeskMetrics metrics() {
        return new QueuedeskMetrics(new SimpleMeterRegistry(), null, new dev.queuedesk.risk.LatencyBuckets());
    }
    @Test void outageServesLastGoodThenUnknown() {
        var metrics = metrics();
        assertEquals(-1.0, metrics.queryOrLastGood("g", () -> { throw new RuntimeException("db down"); }));
        assertEquals(7.0, metrics.queryOrLastGood("g", () -> 7.0));
        assertEquals(7.0, metrics.queryOrLastGood("g", () -> { throw new RuntimeException("db down"); }));
    }
    @Test void gaugesStayIsolatedPerName() {
        var metrics = metrics();
        assertEquals(3.0, metrics.queryOrLastGood("a", () -> 3.0));
        assertEquals(-1.0, metrics.queryOrLastGood("b", () -> { throw new RuntimeException("db down"); }));
    }
}
