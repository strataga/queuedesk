# L1 Runbook: Durable Walking Skeleton

## Start
```sh
./scripts/postgres up   # Postgres 17 on 127.0.0.1:5442 (required)
./mvnw spring-boot:run  # API on 127.0.0.1:8080; Flyway migrates automatically
```

## One instruction, then restart
```sh
curl -s -X POST http://127.0.0.1:8080/api/v1/instructions \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
  -d '{"clientReference":"demo-1","runId":"demo","senderParticipant":"SENDER_A","receiverParticipant":"RECEIVER_B","amountMinor":12500,"currency":"USD"}'
# → 202 with instructionId + statusUrl. Ctrl-C the API, restart it, GET the statusUrl:
# same ID, still RECEIVED. That survival is the durability proof.
```

## Tests and checks
```sh
./mvnw test     # full backend suite (this phase's share: domain, hash, cursor, filter, service, real-Postgres races/rollback, HTTP contracts; current count in VERIFICATION.md)
./scripts/smoke # live CLI + HTTP + restart survival on port 18080 (needs Postgres up)
```

## Outage drill (truthful 503s)
Stop Postgres (`docker stop qdesk-postgres`): POST and GET return
`503 {"code":"UNAVAILABLE",...}` — never a phantom 202. Restart it
(`./scripts/postgres up`), retry the same key: one `200` with the original ID.

## List contract
`GET /api/v1/instructions?runId=&status=&limit=50&cursor=` returns a bare JSON
array in `(created_at, instruction_id)` order. The next-page token arrives in the
`X-Next-Cursor` response header (absent when exhausted). Unknown status or bad
cursor/limit returns 400.

## Shutdown (non-destructive)
Ctrl-C the API. `./scripts/postgres down` stops (keeps data). Named resources only:
container `qdesk-postgres`, volume `qdesk-pgdata`. Never touch host port 5432
(user Homebrew Postgres) or other projects' containers.

## ENV-01 record (verified 2026-10-04)
- JDK 25.0.4.1 via project wrappers (shell default is Homebrew Java 27 — use wrappers)
- Spring Boot 4.0.8, Maven 3.9.11 (checksum-pinned wrapper), spring-jdbc + Flyway starters
- Flyway 11.14.1, flyway-database-postgresql 11.14.1, pgJDBC 42.7.13, HikariCP 7.0.2
- Postgres server 17.11, image `postgres@sha256:d74eeac9a635390a49bc21bd49fccd973de707e2a53a76ac49b552b8712ec46f`
- macOS arm64 (M4 Pro, 24 GB), Docker Desktop engine 29.8.1, container capped at 512 MB
- Config: `QD_DB_URL` / `QD_DB_USER` / `QD_DB_PASSWORD` env vars, loopback lab defaults

## Learner review (LEARN-01, deferred to final review)
Material for the single end-of-project review (no human gate at phase close):
do the MILESTONES first exercise against this backend, then answer: why is a
repeated key a 200 and not a second row? Why can't a Java pre-check replace the
unique index? Why does RECEIVED survive a restart while nothing is processed?
Record a short "what I changed and why" note at the final review.
