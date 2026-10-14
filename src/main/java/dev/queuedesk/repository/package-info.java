/**
 * Instruction persistence: a small repository contract with a Postgres JDBC
 * adapter (explicit SQL, caller-owned transactions) and an in-memory test
 * double that mirrors the same ordering, uniqueness, and transition rules.
 * Also owns the opaque cursor encoding used by paged reads.
 */
package dev.queuedesk.repository;
