/**
 * The pre-clearing fraud-risk gate: frozen feature snapshots, deterministic
 * rules, an optional signal provider (mock by default, live Jev behind a
 * credential and a durable budget cap), lease-guarded evaluation, and
 * audited human reviews. Failures fail closed into visible holds; provider
 * output explains decisions but never decides alone.
 */
package dev.queuedesk.risk;
