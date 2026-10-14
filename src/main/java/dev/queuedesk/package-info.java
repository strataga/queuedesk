/**
 * QueueDesk: a local learning lab for synthetic payment instructions.
 *
 * <p>The application admits fake payments over HTTP with idempotency keys,
 * stores them in Postgres with a transactional outbox, publishes them to
 * IBM MQ, screens them through a pre-clearing risk gate, and settles them
 * against a mock receiver. A read-only Angular dashboard shows every step.
 * Everything binds to loopback; no real money, participants, or cloud
 * services are involved. Start at {@link dev.queuedesk.QueueDeskApplication}.
 */
package dev.queuedesk;
