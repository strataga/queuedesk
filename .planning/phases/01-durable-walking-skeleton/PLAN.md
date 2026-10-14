# Phase 1 Executable Plan: Durable Walking Skeleton

**Status:** In execution.
**Covers:** SK01–SK05, PR02, PR04, PR10, API-01, API-02, DATA-01, ENV-01, LEARN-01.

## Decisions (locked for this phase)

- **D1 Postgres 17** via official multi-arch image, container `qdesk-postgres`, port bound to
  `127.0.0.1:5442` (host 5432 belongs to the user's Homebrew Postgres — do not touch),
  named volume `qdesk-pgdata`. Pinned digest:
  `postgres@sha256:d74eeac9a635390a49bc21bd49fccd973de707e2a53a76ac49b552b8712ec46f`.
- **D2 spring-boot-starter-jdbc (JdbcClient) + Flyway**, Boot-managed versions. Rationale:
  smallest explainable adapter; explicit SQL and transaction boundaries serve LEARN-01.
- **D3 Semantic hash v1:** lowercase-hex SHA-256 over
  `clientReference|runId|senderParticipant|receiverParticipant|amountMinor|currency`
  (exact field order, `|` separator, UTF-8, no whitespace). Version constant `REQUEST_HASH_V1`.
- **D4 List ordering/cursor:** `ORDER BY created_at ASC, instruction_id ASC`, opaque cursor =
  base64url of `created_at|instruction_id`. Default limit 50, max 100. Bad cursor → 400.
  Response stays a bare JSON array (smoke contract); the next-page token travels in the
  `X-Next-Cursor` response header, absent when exhausted.
- **D5 16 KiB enforcement:** servlet filter counts request-body bytes for POST (declared
  Content-Length fast-path plus counted streamed/chunked reads); over 16384 bytes aborts
  with structured 413.
- **D6 Shared local stack** for integration tests (PRD §16 default). No Testcontainers.
- **D7 Amount cap 1..10,000,000** per PRD §11 (PRD precedence over starter 1,000,000,000).

## Work items (in order)

### 1. Dependencies + migration scaffold
- Add `spring-boot-starter-jdbc`, `flyway-core`, `postgresql` (runtime) to pom.xml.
- `src/main/resources/db/migration/V1__init.sql`: `instruction` table (UUID PK, idempotency key
  UNIQUE, request_hash, run_id, participants, amount_minor BIGINT CHECK 1..10000000, currency,
  state, reason, created_at/updated_at UTC, version BIGINT), `instruction_audit` table
  (audit_id PK, instruction_id FK, old_state, new_state, reason, actor, recorded_at),
  indexes on (run_id), (state), idempotency key unique.
- Config: loopback Postgres datasource (env-var creds, local defaults), Flyway enabled.
- Verify: app starts against local Postgres, Flyway migrates clean, re-run is a no-op.

### 2. Postgres repository + admission transaction
- New `JdbcInstructionRepository implements InstructionRepository` (keep in-memory adapter).
  Insert-if-absent by idempotency key; byId; byKey; list by runId/status with cursor.
- `AdmissionService`: validate → hash v1 → single DB transaction inserts instruction +
  audit row (`RECEIVED`) → commit → return. 202 only after commit. 200 exact repeat,
  409 changed semantic payload, correlation-only change stays a repeat.
- Verify: unique-key race (one row); changed-body race (409, original intact); injected
  commit failure (no 202, no rows); invalid input creates zero rows.

### 3. HTTP contracts: 16 KiB, errors, reads
- Byte-counting filter → structured 413. Stable machine codes; no leaked secrets.
- GET by ID (404 unknown). GET list: `runId`, `status`, `limit`, `cursor`.
- Verify: MockMvc + real-HTTP tests for all statuses, Location + statusUrl, chunked
  oversize → 413, cursor stability.

### 4. Amount cap alignment + starter updates
- Cap 1,000,000,000 → 10,000,000; boundary tests; update MILESTONES exercise + README.
- Verify: suite green; dry-run output unchanged per seed.

### 5. Restart survival + runbook + ENV-01 record
- Admit → restart API → GET same ID; DB-down → truthful 503; same-key retry safe.
- Runbook doc; ENV-01 version/digest record; fresh-reader pass on clean checkout.

### 6. Learner-review material (LEARN-01, deferred)
- Exercise + prompts prepared for the final project review; no human step at phase close.

## Must-haves (gate)
- `./mvnw test` green including new real-Postgres tests.
- Invalid inputs create zero rows; commit failure never yields 202; restart preserves ID/state.
- No deps beyond jdbc/flyway/postgresql; no secrets in source/logs.
- `./scripts/smoke` passes on the durable backend.
