# QueueDesk recovery runbook (fresh-reader edition)

You are staring at a lab that misbehaves. This page tells you how to see what
is wrong, what the evidence means, and how to recover — in that order. It
assumes the loopback lab only (`127.0.0.1`, containers `qdesk-postgres` and
`qdesk-mq`, QM `QDESK`). Companion pages: RUNBOOK-L1 (admission), RUNBOOK-L2
(outbox/MQ), RUNBOOK-L3 (retry/quarantine/reconcile), RUNBOOK-RISK (risk
gate + live Jev), RUNBOOK-UI (dashboard), RUNBOOK-K8S (kind).

## 1. Is it alive? (30 seconds)

```sh
curl -s http://127.0.0.1:8080/actuator/health/liveness    # process itself
curl -s http://127.0.0.1:8080/actuator/health/readiness   # process + PG + MQ
curl -s http://127.0.0.1:8080/api/v1/ops/guards | python3 -m json.tool
```

- Liveness UP + readiness DOWN with `mq: DOWN` = broker outage. The app is
  healthy; do not restart it into a loop (OPS-01). Admissions keep returning
  202 and queue in the outbox — that is the design (SC06).
- Liveness DOWN or connection refused = the app is down; see §4.
- `haltTraffic: true` in guards = backlog over 1,000 or disk below the 10 GB
  reserve. The generator refuses new traffic; drain or shut down (§5). Never
  clear pending work to satisfy a guard.

## 2. Where is my instruction?

```sh
curl -s http://127.0.0.1:8080/api/v1/instructions/<id>
curl -s http://127.0.0.1:8080/api/v1/runs/<runId>
```

Read `status` (business state) and `delivery` (transport marker) separately:
a `RECEIVED` instruction with `PENDING_PUBLISH` delivery during an outage is
durable and correct, not stuck. `reconcileOpen` counts instructions still
awaiting receiver confirmation.

Logs are JSON on stdout. Every line carries `component`, `stage`, and the
identity in scope (`runId`, `instructionId`, `eventId`, `deliveryAttempt`,
`correlationId`, `outcome`, plus `traceId`/`spanId` when traced). Filter a
run with `jq`:

```sh
# app stdout is JSON (the app runs as a local process, not a container):
./mvnw spring-boot:run 2>&1 | jq -c 'select(.runId=="demo")'
# (or grep: admission.accepted, outbox.sent, worker.handled,
#  reconcile.resolved, quarantine.reconciled)
```

Metrics that agree with the database live at `/actuator/metrics`
(`queuedesk.admissions.total`, `queuedesk.outcomes.total`,
`queuedesk.outbox.pending`, `queuedesk.pending.confirmation.count`,
`queuedesk.quarantine.count`, `queuedesk.endtoend.duration`, ...). No
instruction, event or run ID ever appears as a metric label — IDs live in
logs and traces. `queuedesk.mq.backlog.estimate.*` is a labeled application
estimate (published but no recorded outcome); the broker truth is
`runmqsc`'s `CURDEPTH` (see RUNBOOK-L2).

## 3. Pending confirmation (the honest middle)

`PENDING_CONFIRMATION` means: the receiver was asked, the reply was lost or
unclear, and the system refuses to guess. Evidence per instruction:

```sql
-- state + reason + attempts + task + receiver truth, in that order
SELECT state, reason FROM instruction WHERE instruction_id = '<id>';
SELECT result, reason, delivery_count FROM processing_attempt WHERE instruction_id = '<id>' ORDER BY attempt_id;
SELECT status, attempts, next_due_at FROM reconciliation_task WHERE instruction_id = '<id>';
SELECT outcome, reason, calls FROM receiver_result WHERE instruction_id = '<id>';
```

- A task with growing `attempts` and a future `next_due_at` = the poller is
  working. Leave it alone.
- `receiver_result` says what the receiver actually did; the instruction
  state follows only on definitive evidence. `unknown-*` fixtures stay
  pending forever — that is the correct answer for a silent receiver, not a
  bug (PR07). There is no replay yet; pending work is projection, not loss.

## 4. Restart and recovery: what survives what

| Restart | Survives | Replays | Proof |
|---|---|---|---|
| App process | PG rows, MQ messages | unacked MQ deliveries → dedup absorbs | SC08 |
| MQ container (`scripts/mq down/up`) | persistent messages | outbox still pending → publishes after recovery | SC06 |
| Postgres container | data volume | publisher/worker resume | outageSurvival test |

Rules:

1. Restart the app with no flags for recovery — fault env vars
   (`QUEUEDESK_FAULT_*`) are drill-only and log a loud banner when armed.
2. After an app crash between commits, the first redelivery records
   `DUPLICATE_SKIPPED` and the receiver `calls` count stays 1: one business
   effect, visible proof. If `calls` is ever > 1 for one instruction, that is
   a real bug — report it.
3. After an MQ restart, the publisher and listener reconnect on their own;
   give them ~30 s before concluding anything.
4. Quarantine is a projection over the backout queue: if `stale` is true in
   `GET /api/v1/quarantine`, the reconciler (not the data) needs attention;
   held messages stay on `QUEUEDESK.INSTRUCTION.BACKOUT` regardless.

## 5. Limits and scoped shutdown

- Admission cap: 1,000 live rows → HTTP 503 `CAPACITY`. Reset the lab only
  deliberately: `./scripts/reset-lab` truncates run data (schema and QM
  config untouched).
- Safeguards: backlog limit 1,000 / disk reserve 10 GB
  (`queuedesk.guards.*`, visible in `/api/v1/ops/guards`). The generator
  exits 2 with everything unsent when halted.
- Bounded by construction: Hikari pool 8, JMS session cache 4, listener
  concurrency 1, publisher batch 10, container memory/log caps in
  `scripts/postgres` and `scripts/mq`, span ring 1,000, OTLP batch queue
  2,048.
- Scoped shutdown: stop the app first (Ctrl-C), then `./scripts/mq down`
  and `./scripts/postgres down`. `down` stops containers without removing
  volumes — nothing durable is lost. Never touch the host Postgres on 5432
  or any container not named `qdesk-*`.

## 6. Telemetry failure is not business failure

Spans export OTLP to `localhost:4318` (configurable,
`queuedesk.telemetry.otlp-endpoint`); with no collector the exporter fails
and counts it (`queuedesk.telemetry.export.spans.failed`) while processing
continues (TG03). The gap between `spans.created` and `export.spans.received`
is SDK-internal buffering/drops — visible by comparison. The in-memory span
ring keeps the last 1,000 finished spans for tests and diagnosis.

## 7. CI progression

`.github/workflows/ci.yml`: unit suites (`-DexcludedGroups=integration`, no
Docker) gate first; the integration job then starts the named PG + MQ and
runs `./scripts/verify`. A final step fails on any skipped suite or missing
report — skips are never reported as passed. Integration suites carry
`@Tag("integration")`; the default local `./mvnw test` still runs everything.

## Learner review (LEARN-03)

Do these in order on a reset lab; each ends with "write down what changed
and why" — the durable artifact is your explanation, not the commands.

1. **Normal run.** `./scripts/generate --send --observe --count 5 --run-id
   lab-normal`. Watch one instruction move `RECEIVED → COMPLETED_SIMULATED`.
   *What changed:* state, delivery marker, attempt rows. *Why:* accepted work
   publishes exactly one event and the worker commits exactly one outcome.
2. **Duplicate run.** Re-run the same command with the same run ID. All five
   come back 200 with original IDs preserved and zero new events.
   *What changed:* nothing durable. *Why:* idempotency keys make repeats
   return the stored admission instead of new work (SC02).
3. **Outage run.** `./scripts/mq down`, admit 5 (all 202, all `RECEIVED`,
   outbox pending 5), `./scripts/mq up`, watch them drain.
   *What changed:* delivery stayed `PENDING_PUBLISH` through the outage, then
   completed. *Why:* acceptance never waits for the broker; the outbox is the
   durable intent (SC06).
4. **Crash window.** Read SC08's evidence (exit 99 between commits), then
   explain in your own words why the redelivery records `DUPLICATE_SKIPPED`
   instead of repeating the business effect. *Why:* the dedup key committed
   with the first result, before the crash.
5. **Lost response.** Admit one `uncertain-*` instruction, watch it go
   `PENDING_CONFIRMATION`, then reconcile from the stored receiver result —
   by original instruction ID, with no replacement instruction.
   *What changed:* state via `RECONCILED` evidence. *Why:* the gateway's
   independent transaction is the source of truth (REC-01/02, D-01/D-03).

Keep your notes next to the scenario reports under `reports/`; the numbers
there are the checkable half of every claim above.
