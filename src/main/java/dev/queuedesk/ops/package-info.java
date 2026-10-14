/**
 * Observability and laboratory safeguards: metrics, tracing, structured log
 * context, bounded span/log rings, health probes, and the traffic guards.
 * Telemetry here is honest about its own limits — sampled, bounded, and
 * counted — and never blocks or breaks business work.
 */
package dev.queuedesk.ops;
