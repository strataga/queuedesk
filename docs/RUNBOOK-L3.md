# L3 Runbook: Bounded Retry and Quarantine

## Start
```sh
./scripts/postgres up   # Postgres 17 on 127.0.0.1:5442 (required)
./scripts/mq up         # IBM MQ dev server on 127.0.0.1:1414 (required)
./scripts/mq setup      # queues + BOTHRESH/BOQNAME + auth records (re-run after any recreate)
./mvnw spring-boot:run  # API + publisher + worker + quarantine reconciler
```

## How poison flows (QUAR-01, RETRY-01)
Routing is application-owned: the worker counts `JMSXDeliveryCount` and, after
three failed processing deliveries, moves the message to
`QUEUEDESK.INSTRUCTION.BACKOUT` in the same transacted JMS session and commits,
so the move is atomic. The app never consumes from backout; the reconciler
browses it every 15s into `quarantine_record` rows.

- Poison (bad envelope, impossible invariant): rolls back unpaced; routes on
  delivery 3 with a `ROUTED` attempt row.
- Transient (DB down, faults): rolls back with backoff pacing outside the
  business transaction (`backoffMs`: ~2s/4s/8s); routes only if still failing
  on delivery 3.
- Route failure (backout missing, no rights, full): the move throws, a
  `ROUTE_FAILED` row is written best-effort, and the consumer HALTS with the
  message retained. Repair routing, then restart the app (or the container).
- Business rejection is not retry or poison: it commits `REJECTED` and is
  acknowledged normally.

Delivery detail now spans all four values: `QUARANTINED` (held row exists) >
`RETRYING` (a FAILED/UNSUPPORTED attempt on a non-terminal instruction) >
`PUBLISHED` > `PENDING_PUBLISH`.

## Inspect quarantine (QUAR-02)
```sh
curl -s http://127.0.0.1:8080/api/v1/quarantine
# → {lastReconciledAt, stale, lastError, items:[{eventId, instructionId, reason, firstSeenAt, lastSeenAt}]}
```
`stale=true` means the heartbeat aged past 60s (broker unreachable or
reconciler dead) — the items shown may lag the queue. Malformed messages
without an eventId are identified by body digest and never fabricate an
instruction. Observing never authorizes replay (deferred).

```sql
SELECT result, delivery_count, reason FROM processing_attempt WHERE event_id = '<event>' ORDER BY attempt_id;
-- Three failures with delivery_count 1,2,3, then one ROUTED row: the app and
-- provider counters side by side.
```

## Lost replies and reconciliation (PR07, REC-01/02/03)
The receiver gateway stores one durable result per instruction in its OWN
transaction first; only then may the reply be "lost" (`uncertain-*` fixtures)
or the call stay unclear (`flaky-*`, `unknown-*`). On a lost/uncertain reply
the worker atomically records `PENDING_CONFIRMATION` + dedup key + one
reconciliation task, so MQ redelivery absorbs as a duplicate instead of
repeating a business effect. A poller (every 10s, batch of 10) looks the
original instruction up: definitive receiver evidence settles it
(`COMPLETED_SIMULATED`/`REJECTED` via legal transitions only); still-unknown
stays pending with visible attempts/due time; a reissue happens only after a
lookup found no definitive result — a replacement instruction is never created.

```sql
SELECT status, COUNT(*) FROM reconciliation_task GROUP BY 1;
SELECT state, reason FROM instruction WHERE state = 'PENDING_CONFIRMATION' LIMIT 5;
-- SC09b's unknowns remain OPEN honestly; SC09a's lost replies all reconcile.
```

Generator fixtures: `uncertain-` (reply lost, result stored), `flaky-`
(first call crashes, reissue resumes), `unknown-` (never resolves — stays
pending, the honest answer for a silent receiver).

## Why not provider routing
Probed 2026-10-05: with `BOTHRESH(3)` the JMS client intercepts the fourth GET
itself (`WMQPoison`). When its requeue fails (no `+passall`, missing queue) it
throws `JMSWMQ1080` inside the listener container's receive call, where no
application hook can turn it into a clean stop — the consumer would spin in an
error loop with the listener blind. So `BOTHRESH(0)` disables client poison
logic and the app counts and moves explicitly; `BOQNAME` stays as
documentation. The requeue grant (`+passall`, probed minimal) remains because
any context-preserving put needs it.

## Poison drill (SC10 preliminary, app running with roles on)
```sh
./scripts/poison poison-demo '{not valid json'
sleep 20   # three fast deliveries, then the app routes on the third
curl -s http://127.0.0.1:8080/api/v1/quarantine | python3 -m json.tool
# → one held item, reason UNPARSEABLE_ENVELOPE, stale=false; the consumer is
# still running and the input queue is drained. Row evidence:
# SELECT result, delivery_count FROM processing_attempt WHERE event_id IS NULL
#   ORDER BY attempt_id;  → UNSUPPORTED:1, UNSUPPORTED:2, UNSUPPORTED:3, ROUTED:3
```
Full proof is automated in `WorkerTest` (route-after-three, broken-route halt
with preservation, pacing, reconciliation, freshness); SC10 acceptance runs
in `./scripts/verify` against the assembled system.

## Tests and checks
```sh
./mvnw test        # full backend suite vs real PG + real MQ (no schedulers in test contexts; current count in VERIFICATION.md)
./scripts/smoke    # durable-backend contract (roles pinned off)
./scripts/scenarios  # SC01-SC05, SC09a-SC09b, SC11-SC12 (no poison in fixtures; unaffected); SC06-SC08 + SC10 run inside ./scripts/verify
./scripts/verify   # gate 0 clean-lab reset, then all three gates in order
```
Builds inside smoke/scenarios use `-DskipTests`: the suite runs once, in
gate 1, on a freshly reset lab — never as a build side effect on dirty data.

## Shutdown (non-destructive)
Ctrl-C the API. `./scripts/mq down` and `./scripts/postgres down` stop
containers (volumes keep data). Named resources only: `qdesk-mq`/`qdesk-mqm2`,
`qdesk-postgres`/`qdesk-pgdata`. Never touch host port 5432 or other
projects' containers.
