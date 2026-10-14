package dev.queuedesk.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production marker writer: commits the published marker in its own new
 * database transaction, strictly after the broker confirms the send. Never
 * the reverse. A separate bean (rather than a method on the publisher) is
 * what makes the {@code REQUIRES_NEW} boundary real instead of a self-call
 * Spring would silently ignore.
 */
@Component
public class DbMarkerWriter implements OutboxPublisher.MarkerWriter {
    private final OutboxRepository outbox;
    public DbMarkerWriter(OutboxRepository outbox) { this.outbox = outbox; }
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(OutboxRepository.DueRow row) { outbox.markPublished(row.outboxId()); }
}
