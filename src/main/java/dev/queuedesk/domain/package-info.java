/**
 * Pure domain types with no framework dependencies: the payment instruction
 * itself, its durable stored form, the business state machine, and the
 * request fingerprint that tells a safe retry from an idempotency conflict.
 * Validation lives in constructors, so invalid values cannot exist anywhere
 * else in the system.
 */
package dev.queuedesk.domain;
