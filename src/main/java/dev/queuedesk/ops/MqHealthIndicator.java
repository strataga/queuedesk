package dev.queuedesk.ops;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import jakarta.jms.Connection;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

// MQ dependency health (OPS-01): a cheap connect-and-close probe. DOWN here
// degrades readiness only — liveness is the process itself, so a broker
// outage never causes a restart loop. Never throws; an unknown broker is
// reported, not hidden.
@Component("mq")
public class MqHealthIndicator implements HealthIndicator {
    // A hung broker must not stall the actuator thread: bound the probe.
    private static final java.util.concurrent.ExecutorService PROBE =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                var thread = new Thread(r, "mq-health-probe");
                thread.setDaemon(true);
                return thread;
            });
    private final MQConnectionFactory factory;
    public MqHealthIndicator(MQConnectionFactory factory) { this.factory = factory; }
    @Override
    public Health health() {
        var future = PROBE.submit(() -> {
            try (Connection connection = factory.createConnection()) {
                connection.start();
                return factory.getQueueManager();
            }
        });
        try {
            return Health.up().withDetail("queueManager", future.get(5, java.util.concurrent.TimeUnit.SECONDS)).build();
        } catch (Exception unreachable) {
            future.cancel(true);
            return Health.down().withDetail("error", unreachable.toString()).build();
        }
    }
}
