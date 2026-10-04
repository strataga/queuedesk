# QueueDesk Brownfield Baseline

**Inspected:** 2026-10-04 during planning intake.
**Current stage:** L0 foundation plus pre-L1 in-memory HTTP rehearsal.

## Existing Boundaries

| Area | Evidence | Present behavior | Gap to target |
|------|----------|------------------|---------------|
| Domain | src/main/java/dev/queuedesk/domain/PaymentInstruction.java | Immutable record, normalized tokens, fixed participants, USD integer amounts | Starter cap 1,000,000,000 differs from PRD 10,000,000 |
| Stored data | src/main/java/dev/queuedesk/domain/StoredInstruction.java | UUID, key, correlation, payload, receivedAt; fixed RECEIVED | Durable state/reasons/version/timestamps absent |
| Admission | src/main/java/dev/queuedesk/service/AdmissionService.java | Synchronized check/repeat/conflict; cap 1,000 records | No DB transaction/hash/unique constraint; all() count not a durable guard |
| Repository | src/main/java/dev/queuedesk/repository/ | Interface plus synchronized LinkedHashMap | Data and keys lost on restart; no Postgres/migrations |
| HTTP | src/main/java/dev/queuedesk/http/ | POST, ID read, runId+limit list, error advice | 16 KiB/413, status filter/cursor, precise reason codes and DB-unavailable semantics missing |
| Generator | tools/Generate.java; scripts/generate | Seeded dry run; explicit loopback send; normal/duplicate/invalid/burst; bounded rate/count/time | No concurrency option, conflict/rejection fixtures, outcome polling/distributions or full PRD assertions |
| Configuration | pom.xml; src/main/resources/application.properties | Boot 4.0.8, Java 25, loopback server, unknown-field/fraction rejection | No DB/MQ/health/UI dependencies |
| Tests | src/test/java/dev/queuedesk/ | Five domain/service/HTTP tests | No DB/MQ, crash, business-outcome or browser coverage |
| Scripts/docs | scripts/smoke.py; README.md; docs/ | Historical smoke/restart-loss demonstration; preserved PRD/assets | Durable runbook, scenario evidence and recovery guide remain future |

## Fresh Verification

`./mvnw test` completed successfully during intake:
- Tests: 5
- Failures: 0
- Errors: 0
- Skipped: 0

Observed runtime: Java 25.0.4.1. The build emitted Maven/Mockito/JDK agent warnings; tests passed. This does not establish future library support or any distributed behavior.

## Prior Evidence

docs/VERIFICATION.md records package, smoke, real HTTP and restart-loss checks earlier on 2026-10-04. This intake did not rerun package/smoke or start infrastructure. The prior results are attributed, not claimed as freshly measured.

## Preserve and Evolve

Keep plain business rules independent of Spring, constructor injection and focused layers. Replace the persistence boundary deliberately rather than carrying the single-process lock into a durability claim. Extend the Java CLI's deterministic contract while making changed count semantics explicit.

The source diagrams depict future architecture. No MQ, Postgres, worker, simulated completion, durable receiver truth, reconciliation, quarantine or Angular exists in the baseline.

## Repository State at Intake

The repository was on unborn main with no commits; all existing application/source files were untracked. Limit this task's staged/committed files to .planning/. The user-owned starter remains untouched by planning generation.
