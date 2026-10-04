# Phase 1: Durable walking skeleton - Context

**Gathered:** 2026-10-04
**Status:** Ready for phase research and planning
**Origin:** Synthesized from the supplied PRD, implementation docs and reviewed images. This is source-derived context, not a claimed discuss-phase interview.
**Requirements:** SK01, SK02, SK03, SK04, SK05, PR02, PR04, PR10, API-01, API-02, DATA-01, ENV-01, LEARN-01

<domain>
## Phase Boundary

A generated instruction is durably admitted, repeatable by key, and retrievable after the API restarts.

Stage: L1. Dependency: existing starter.
</domain>

<decisions>
## Source Decisions and Planning Defaults

- **D-01:** Use the existing Java/Maven/Spring codebase and constructor-injected boundary; replace map-only durability with Postgres.
- **D-02:** PRD target amount cap is 10,000,000, resolving the starter's 1,000,000,000 discrepancy. Update implementation notes and tests during execution.
- **D-03:** Use unique database constraints plus transactions for idempotency; the existing synchronized method is insufficient across processes.
- **D-04:** Until MQ arrives, RECEIVED is the business truth and an explicitly unconfigured delivery field is valid. No worker outcome is implied.
- **D-05:** Keep synthetic-only data and loopback binding; preserve the existing exact-loopback generator restriction unless a deliberate future change is reviewed.

These decisions reflect the PRD and documented precedence resolutions. Version/tool/library choices below remain open until researched; there are no newly asserted locked ADRs.
</decisions>

<specifics>
## Suggested Plan Slices

1. Align domain amount bounds and stable HTTP error contracts; document canonical payload hashing and preserve correlation as metadata.
2. Introduce migrations and a Postgres repository/use-case transaction; prove database uniqueness, exact repeats, conflicts, rollback and commit failure.
3. Complete 16 KiB enforcement and bounded read pagination/status filters with deterministic ordering and documented response shape.
4. Add real-Postgres restart/rollback evidence, configuration examples, setup/shutdown runbook and the L1 learning demonstration.

These are planning inputs, not executable PLAN.md files. Resolve the open implementation choices, then create bounded plans with tasks, verification and must-haves.

## Observable Acceptance

1. A new reader starts one local Postgres and the API, applies migrations, explicitly sends a synthetic instruction and retrieves the same RECEIVED record after an API restart.
2. Invalid fields/participants/amounts create no rows; oversized bodies return 413; failed commits return no successful admission. Valid amount boundaries match 1..10,000,000 minor units.
3. Concurrent same-key submissions yield one durable identity; changed business payload returns 409 without overwriting it, while changed correlation metadata remains a repeat.
4. GET supports unknown-ID 404 and bounded run/status pagination with stable ordering; database/capacity errors expose safe, truthful responses.
5. Dry-run/local defaults and bounded configuration remain usable, and the learner can explain record validation, injected layers, the unique constraint and commit-before-202.

## Evidence

Real Postgres migration, unique-key race, invalid-input zero rows, rollback/commit failure, HTTP size/read contracts and application restart survival. Existing five tests remain baseline only.
</specifics>

<canonical_refs>
## Canonical References

- `docs/QueueDesk_PRD.md §§3–4, 7, 9, 11, 16–20`
- `docs/MILESTONES.md`
- `docs/images/QueueDesk_Spring_Layers.png`
- `docs/images/QueueDesk_Learning_Path.png`
- `.planning/REQUIREMENTS.md` — requirement definitions and owning phase.
- `.planning/VALIDATION.md` — scenario and failure evidence.
- `.planning/INGEST-CONFLICTS.md` — target versus starter resolutions.
</canonical_refs>

<code_context>
## Existing Code and Integration Points

- `src/main/java/dev/queuedesk/domain/PaymentInstruction.java`
- `src/main/java/dev/queuedesk/domain/StoredInstruction.java`
- `src/main/java/dev/queuedesk/service/AdmissionService.java`
- `src/main/java/dev/queuedesk/repository/InstructionRepository.java`
- `src/main/java/dev/queuedesk/repository/InMemoryInstructionRepository.java`
- `src/main/java/dev/queuedesk/http/InstructionController.java`
- `src/main/java/dev/queuedesk/http/ApiErrors.java`
- `pom.xml`
- `src/test/java/dev/queuedesk/`

Future-phase references describe integration points that do not yet exist at intake; re-inspect actual code after dependencies complete.
</code_context>

## Research and Open Implementation Choices

- Confirm employer-aligned versions if available; record existing Java 25/Boot 4.0.8/Maven 3.9.11, then verify a supported Postgres/driver/migration combination from primary documentation.
- Choose the smallest explainable Spring JDBC/JPA adapter and migration tool, with real PostgreSQL transaction tests.
- Specify canonicalization/hash version, duplicate-race recovery and an HTTP response boundary that cannot send 202 before commit.
- Choose deterministic cursor ordering (including a tie-breaker), cursor validation and the exact response envelope; verify bounded streaming-body enforcement.
- Measure local container-engine availability, OS/CPU, disk reserve and memory before starting the dependency.

<deferred>
## Deferred Scope

Outbox/MQ delivery begins in Phase 2; business outcomes in Phase 3; full generator observation in Phase 4.
</deferred>

---
*Phase: 01-durable-walking-skeleton*
*Context gathered: 2026-10-04*
