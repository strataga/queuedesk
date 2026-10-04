# Phase 4: Generator reports and core MVP - Context

**Gathered:** 2026-10-04
**Status:** Ready for phase research and planning; execution waits for prerequisites
**Origin:** Synthesized from the supplied PRD, implementation docs and reviewed images. This is source-derived context, not a claimed discuss-phase interview.
**Requirements:** PR01, GN01, GN02, GN03, GN04, GN05, GN06, GN07, GN08, GEN-01, GEN-02, TEST-01

<domain>
## Phase Boundary

A learner can run every core traffic scenario and account for admission, completion, rejection and uncertainty in a reproducible report.

Stage: L2 exit. Dependency: Phase 3.
</domain>

<decisions>
## Source Decisions and Planning Defaults

- **D-01:** Java remains the generator language and the script is the primary producer.
- **D-02:** Defaults: 20 instructions, 2/sec, concurrency 1, 5-second requests, 60-second sending cap. Hard caps: 500 submissions, 10/sec, concurrency 4, five minutes.
- **D-03:** Observation starts as a separate bounded step: two-second polling, 90-second completion window; stop new work when a sending deadline or stop signal occurs.
- **D-04:** SC02 means 20 unique plus five exact repeats. Expose both unique and attempted counts; do not preserve the starter's incompatible count interpretation silently.
- **D-05:** After timeout/503, preserve exact key/body; never automatically retry validation/conflict failures.
- **D-06:** Outcome and fixture faults are deterministic inputs, not promises of deterministic UUIDs, latency or thread scheduling.
- **D-07:** Run metadata must be usable by the later dashboard through API evidence; specify a local run-manifest persistence/import contract without letting the CLI write the database directly.

These decisions reflect the PRD and documented precedence resolutions. Version/tool/library choices below remain open until researched; there are no newly asserted locked ADRs.
</decisions>

<specifics>
## Suggested Plan Slices

1. Refactor the finite Java CLI into testable generation/submission/observation/report boundaries while preserving deterministic dry-run output.
2. Implement explicit attempt/unique counts, conflict/rejection fixtures, bounded concurrency/duration and identity-safe uncertain-admission handling.
3. Add separate completion polling, machine-readable reports/latency distributions and stop/deadline outcome semantics.
4. Run the core scenario suite, add explicit CI profiles and complete the core MVP runbook/demo gate.

These are planning inputs, not executable PLAN.md files. Resolve the open implementation choices, then create bounded plans with tasks, verification and must-haves.

## Observable Acceptance

1. A seed-42 normal run produces 20 distinct COMPLETED_SIMULATED outcomes within its 90-second observation window, with no unresolved instruction.
2. The duplicate scenario reports 25 attempts for 20 unique instructions/events; conflict and invalid scenarios preserve original data and create no prohibited rows.
3. Dry-run, loopback, identity-preserving retry and workload limits remain enforced; burst and stop/deadline cases account for every planned, attempted and unresolved submission.
4. Reports preserve seed/fixture/configuration/run metadata, separate HTTP/end-to-end latency distributions and honest exit codes; observation polls every two seconds independently of sending.
5. SC01–SC05 and SC11–SC12 pass with stored evidence, and a new reader can run the full local CLI-to-outcome loop without manual DB editing.

## Evidence

SC01–SC05, SC11 and SC12 with request counts, distinct identities, outbox counts, outcome counts, unresolved records and separate latency intervals. Core MVP gate only; reliability gate remains open.
</specifics>

<canonical_refs>
## Canonical References

- `docs/QueueDesk_PRD.md §§4, 13–14, 16–17, 21–22`
- `README.md`
- `docs/images/QueueDesk_Architecture.png`
- `.planning/REQUIREMENTS.md` — requirement definitions and owning phase.
- `.planning/VALIDATION.md` — scenario and failure evidence.
- `.planning/INGEST-CONFLICTS.md` — target versus starter resolutions.
</canonical_refs>

<code_context>
## Existing Code and Integration Points

- `tools/Generate.java`
- `scripts/generate`
- `scripts/smoke.py`
- `scripts/smoke`
- `src/main/java/dev/queuedesk/http/InstructionController.java`

Future-phase references describe integration points that do not yet exist at intake; re-inspect actual code after dependencies complete.
</code_context>

## Research and Open Implementation Choices

- Choose output/report schema and backward-compatible CLI behavior with clear attempted-versus-unique semantics.
- Specify cancellation, bounded concurrency, monotonic deadlines and how unknown admissions are rediscovered using original identities.
- Define HTTP versus end-to-end latency start/end points and avoid including unresolved instructions in completed latency.
- Design run-manifest/API provenance for expected counts and fixture versions needed by UI-04; keep fault controls restricted to the lab.

<deferred>
## Deferred Scope

Injected infrastructure/crash scenarios are local harness work in Phase 7; the generator must not introduce public fault parameters.
</deferred>

---
*Phase: 04-generator-and-core-mvp*
*Context gathered: 2026-10-04*
