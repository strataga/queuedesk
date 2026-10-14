package dev.queuedesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * QueueDesk entry point.
 *
 * <p>QueueDesk is a local learning lab for synthetic payment instructions:
 * idempotent HTTP admission, a transactional outbox over IBM MQ, a
 * pre-clearing risk gate, and simulated settlement. Everything runs on
 * loopback with fake participants and fake money. Component scanning starts
 * here, so every layer below {@code dev.queuedesk} is picked up automatically.
 */
@SpringBootApplication
public class QueueDeskApplication {
    public static void main(String[] args) {
        SpringApplication.run(QueueDeskApplication.class, args);
    }
}
