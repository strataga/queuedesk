/**
 * The worker side: single-consumer MQ listener, exactly-one-outcome
 * processing with durable dedup, mock clearing and receiver fixtures,
 * poison-message quarantine with browse-only reconciliation, and bounded
 * reconciliation of uncertain receiver answers from stored evidence.
 */
package dev.queuedesk.worker;
