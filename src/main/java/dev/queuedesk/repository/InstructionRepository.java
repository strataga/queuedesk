package dev.queuedesk.repository;

import dev.queuedesk.domain.StoredInstruction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for payment instructions.
 *
 * <p>An interface here is a runtime contract with two implementations: the
 * JDBC adapter for Postgres and an in-memory adapter for fast unit tests.
 * Both honor the same ordering and uniqueness rules, so behavior proven
 * against the test double carries over to the database. Transaction
 * boundaries belong to the caller (the service layer); implementations only
 * execute statements.
 */
public interface InstructionRepository {
    /** Looks up an instruction by its unique idempotency key. */
    Optional<StoredInstruction> byKey(String key);
    /** Looks up an instruction by its public ID. */
    Optional<StoredInstruction> byId(UUID id);
    /** Returns every instruction in admission order. Intended for tests and small lab reads. */
    List<StoredInstruction> all();
    /**
     * Inserts a new instruction (plus its opening audit row). Implementations
     * fail on a duplicate idempotency key instead of overwriting, so the
     * service can resolve the race explicitly.
     */
    void save(StoredInstruction instruction);
    /** Counts stored instructions. The JDBC adapter overrides this with a real {@code COUNT(*)}. */
    default long count() { return all().size(); }
    /**
     * Bounded page in {@code (created_at, instruction_id)} order, ascending
     * or descending when {@code newestFirst}. An empty cursor starts at the
     * beginning of the chosen direction; a {@code null} runId or status
     * disables that filter. Non-null {@code since}/{@code until} bound
     * {@code created_at}, inclusive at both ends (the caller rejects
     * {@code since > until}). Returns up to {@code limit + 1} rows so the
     * caller can tell whether another page exists. Cursors are
     * direction-scoped: a cursor minted on an ascending page must not be
     * replayed on a descending one.
     */
    List<StoredInstruction> list(String runId, String status, int limit, Optional<PageCursor> cursor,
            java.time.Instant since, java.time.Instant until, boolean newestFirst);
    /**
     * Conditional business transition: moves state {@code from} to {@code to}
     * with a reason, an audit row, and a version bump, all in the caller's
     * transaction. Returns {@code false} when the row is not in the expected
     * from-state; the caller decides whether that means duplicate delivery,
     * a conflict, or a bug.
     */
    boolean transition(java.util.UUID id, String from, String to, String reason, String actor);
    /** Run summary support: state counts for one runId (empty when the run is unknown). */
    java.util.Map<String, Long> countByState(String runId);
    /**
     * Lab-wide census for the overview: state counts over all runs,
     * optionally windowed by admission time (inclusive at both ends,
     * {@code null} means unbounded).
     */
    java.util.Map<String, Long> countLabByState(java.time.Instant since, java.time.Instant until);
    /**
     * Throughput history for the overview graph: {@code COMPLETED_SIMULATED}
     * counts per {@code bucketSeconds} bucket, keyed by epoch-aligned bucket
     * start. Completion is terminal (it never regresses), so each completion
     * lands in exactly one bucket. Only non-empty buckets are returned; the
     * caller zero-fills the gaps. The window bounds completion time,
     * inclusive at both ends, {@code null} means unbounded.
     */
    java.util.Map<java.time.Instant, Long> completionsPerBucket(java.time.Instant since,
            java.time.Instant until, long bucketSeconds);
    /**
     * Serializes the admission check-and-insert inside the caller's
     * transaction. The JDBC adapter takes a transaction-scoped advisory
     * lock; the single-threaded test double needs no lock and uses this
     * no-op.
     */
    default void lockForAdmission() {}
    /**
     * Newest-first lab-wide recents for the overview table: first page only,
     * no cursor, ordered by {@code (created_at, instruction_id)} descending.
     */
    java.util.List<StoredInstruction> recent(int limit);
}
