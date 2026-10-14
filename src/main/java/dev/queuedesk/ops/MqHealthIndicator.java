package dev.queuedesk.ops;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import jakarta.jms.Connection;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * MQ dependency health: a cheap connect-and-close probe for the actuator
 * readiness group.
 *
 * <p>DOWN here degrades readiness only. Liveness is the process itself, so
 * a broker outage can never cause a restart loop; the app keeps accepting
 * payments into the outbox while MQ recovers. The probe runs on a daemon
 * thread with a 5-second bound so a hung broker cannot stall the actuator,
 * and it never throws: an unknown broker is reported, not hidden.
 */
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
