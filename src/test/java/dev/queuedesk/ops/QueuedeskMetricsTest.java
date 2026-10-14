package dev.queuedesk.ops;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gauge fallback without Spring or a database: live values are cached,
 * outages serve the last good reading, and a gauge that never read serves
 * -1 (unknown, never a fake zero). Gauges stay isolated per name.
 */
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
