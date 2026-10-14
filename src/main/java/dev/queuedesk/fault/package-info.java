/**
 * Controlled crash windows for failure drills. One-shot latches sit exactly
 * on commit boundaries so drills can kill (or, in tests, fault) the process
 * at the microsecond that matters. Halt mode requires an explicit
 * environment opt-in and is unreachable from HTTP.
 */
package dev.queuedesk.fault;
