package dev.queuedesk.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Production marker writer: the published marker commits in its own new database
// transaction, strictly after the broker confirms the send (never the reverse).
@Component
public class DbMarkerWriter implements OutboxPublisher.MarkerWriter {
    private final OutboxRepository outbox;
    public DbMarkerWriter(OutboxRepository outbox) { this.outbox = outbox; }
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(OutboxRepository.DueRow row) { outbox.markPublished(row.outboxId()); }
}
