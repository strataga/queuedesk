package dev.queuedesk.repository;

import dev.queuedesk.domain.StoredInstruction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// An interface is a runtime contract: the JDBC adapter implements it for Postgres,
// the in-memory adapter for fast unit tests. Both honor the same ordering contract.
public interface InstructionRepository {
    Optional<StoredInstruction> byKey(String key);
    Optional<StoredInstruction> byId(UUID id);
    List<StoredInstruction> all();
    void save(StoredInstruction instruction);
    default long count() { return all().size(); }
    // Bounded page in (created_at, instruction_id) order, ascending or
    // descending when newestFirst. Empty cursor starts at the beginning of the
    // chosen direction; null runId/status disables that filter. Non-null
    // since/until bound created_at (inclusive both ends; caller rejects since
    // > until). Returns up to limit+1 rows so the caller can tell whether
    // another page exists. Cursors are direction-scoped: a cursor minted on an
    // ascending page must not be replayed on a descending one.
    List<StoredInstruction> list(String runId, String status, int limit, Optional<PageCursor> cursor,
            java.time.Instant since, java.time.Instant until, boolean newestFirst);
    // Conditional business transition: moves state from→to with reason, audit row
    // and version bump in the caller's transaction. Returns false when the row is
    // not in the expected from-state (caller decides: duplicate, conflict, bug).
    boolean transition(java.util.UUID id, String from, String to, String reason, String actor);
    // Run summary support: state counts for one runId (empty when unknown).
    java.util.Map<String, Long> countByState(String runId);
    // Lab-wide census for the overview: state counts over all runs, optionally
    // windowed by admission time (inclusive both ends, nulls unbounded).
    java.util.Map<String, Long> countLabByState(java.time.Instant since, java.time.Instant until);
    // Throughput history for the overview graph: COMPLETED_SIMULATED counts per
    // bucketSeconds bucket, keyed by epoch-aligned bucket start. Completion is
    // terminal (never regresses), so each completion lands in exactly one
    // bucket; only non-empty buckets are returned (the caller zero-fills).
    // Window bounds completion time (inclusive both ends, nulls unbounded).
    java.util.Map<java.time.Instant, Long> completionsPerBucket(java.time.Instant since,
            java.time.Instant until, long bucketSeconds);
    // Serialize admission check-and-insert inside the caller's transaction.
    // The JDBC adapter takes a transaction-scoped advisory lock; the
    // single-threaded test double needs no lock and uses this no-op.
    default void lockForAdmission() {}
    // Newest-first lab-wide recents for the overview table (mockup 01): first
    // page only, no cursor — (created_at, instruction_id) descending.
    java.util.List<StoredInstruction> recent(int limit);
}
