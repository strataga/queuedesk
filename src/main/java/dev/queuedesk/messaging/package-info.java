/**
 * Transactional outbox over IBM MQ: the versioned event envelope, explicit
 * MQ wiring, durable publish intent, and the polling publisher. The rule
 * everywhere in this package is confirm first, mark second — an unmarked
 * outbox row always means delivery is still owed, across outages and
 * restarts.
 */
package dev.queuedesk.messaging;
