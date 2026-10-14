# L2 Runbook: Outbox and MQ Publishing

## Start
```sh
./scripts/postgres up   # Postgres 17 on 127.0.0.1:5442 (required)
./scripts/mq up         # IBM MQ dev server on 127.0.0.1:1414 (required)
./scripts/mq setup      # queue + auth records (idempotent, re-run after recreate)
./mvnw spring-boot:run  # API on 127.0.0.1:8080; publisher polls every second
```

## Topology
Container `qdesk-mq`, volume `qdesk-mqm2` (QM data survives container recreate),
queue manager `QDESK`, channel `DEV.APP.SVRCONN`, queue
`QUEUEDESK.INSTRUCTION.IN` with `DEFPSIST(YES)`. User `app` authenticates with
the secret in `.cache/mq-secrets/mqAppPassword` via the container's developer
auth plugin (`MQ_CONNAUTH_USE_HTP=true`); no OS user is created for it.
Auth failures are visible at `/var/mqm/errors/simpleauth.json` in the container
(`DEBUG=true` adds per-attempt verdicts).

## Admit one, watch it publish
```sh
curl -s -X POST http://127.0.0.1:8080/api/v1/instructions \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: l2-1' \
  -d '{"clientReference":"l2-1","runId":"l2","senderParticipant":"SENDER_A","receiverParticipant":"RECEIVER_B","amountMinor":5000,"currency":"USD"}'
# → 202. Admission commits the instruction AND its outbox event in one DB
# transaction; the publisher then sends one persistent TextMessage and marks
# the row PUBLISHED in a second transaction.
docker exec qdesk-mq bash -c "echo 'DISPLAY QL(QUEUEDESK.INSTRUCTION.IN) CURDEPTH' | runmqsc QDESK"
# Browse the envelope: schemaVersion/eventType/eventId/instructionId/
# correlationId/runId/occurredAt + validated payload with requestHash.
```

## Backlog evidence
```sql
SELECT COUNT(*) AS pending FROM outbox WHERE published_at IS NULL;
SELECT MIN(created_at) AS oldest_pending FROM outbox WHERE published_at IS NULL;
SELECT event_id, publish_attempts, next_due_at, last_error FROM outbox
  WHERE published_at IS NULL ORDER BY outbox_id LIMIT 10;
```
The app logs one `outbox.batch` line per poll (sent/due/pending/oldest age)
and one `outbox.publish.failed` line per failure (eventId/attempts/backoff).

## Outage drill (no loss, same eventIds)
```sh
# Admit 2 instructions, then:
docker stop qdesk-mq
# Publisher keeps rows pending, records attempts + next_due_at, backs off with
# jitter; admissions still return 202 (durable intent, not delivery).
./scripts/mq up   # data volume survives; QM returns to Running
# Publisher drains the backlog automatically; browsed envelopes carry the same
# eventIds as before the outage. Automated in OutboxPublisherTest#outageSurvival.
```

## Crash note (duplicate delivery is expected)
If the process dies after the MQ commit but before the PUBLISHED marker, the
row stays pending and the next poll re-sends the SAME eventId. The worker
absorbs the redelivery via the `processed_message` dedup key (visible as
`DUPLICATE_SKIPPED` attempts) — one business result per eventId. Automated in
OutboxPublisherTest#crashAfterCommitPreservesEventId.

## Worker (Phase 3): one consumer, one outcome
The same `./mvnw spring-boot:run` also starts the worker: concurrency 1, local
transacted JMS session, business result + `processed_message` dedup key in one
DB transaction before the delivery commits. Disable either role with
`--queuedesk.publisher.enabled=false` / `--queuedesk.worker.enabled=false`.

```sh
# Admit one, wait a few seconds, GET it:
# RECEIVED → BUSINESS_ACCEPTED → COMPLETED_SIMULATED (each with an audit row).
# Admit with clientReference "reject-*" for a committed REJECTED instead.
curl -s http://127.0.0.1:8080/api/v1/instructions/<id>
# → status + reason + delivery (PENDING_PUBLISH/PUBLISHED) + receivedAt/updatedAt.
```

Dedup evidence (PR05/DATA-02):
```sql
SELECT COUNT(*) FROM processed_message WHERE instruction_id = '<id>'; -- always 1
SELECT result, reason FROM processing_attempt WHERE instruction_id = '<id>' ORDER BY attempt_id;
SELECT old_state, new_state, reason, actor FROM instruction_audit WHERE instruction_id = '<id>' ORDER BY audit_id;
```
Redeliveries append `DUPLICATE_SKIPPED` attempts and never touch the outcome.
A malformed envelope records an `UNSUPPORTED` attempt row and rolls back; after
three failed deliveries the app routes it to backout (see RUNBOOK-L3) instead
of stopping the consumer.

Business states (STATE-01 + risk): RECEIVED → BUSINESS_ACCEPTED, REJECTED or
RISK_HELD; BUSINESS_ACCEPTED → COMPLETED_SIMULATED, REJECTED or
PENDING_CONFIRMATION; RISK_HELD → RECEIVED or REJECTED (review actions
deferred). Terminal states never regress; delivery detail is a separate
dimension.

## Scenario catalog (Phase 4): script-to-outcome loop
```sh
./scripts/scenarios  # SC01-SC05, SC11-SC12 vs live PG+MQ, reports under reports/
./scripts/verify     # explicit gates: test suite → smoke → scenarios
```
One scenario by hand (app running with roles on):
```sh
./scripts/generate --scenario normal --seed 42 --count 20 --run-id demo-42 \
  --send --observe --report reports/demo-42.json
curl -s http://127.0.0.1:8080/api/v1/runs/demo-42  # admitted, byState, outbox counts
```
Reports carry seed, fixture version, config, runId, timestamps, admission and
outcome counts, separate HTTP/end-to-end latency distributions, unresolved
work and assertion failures. Exit 0 = assertions pass; 1 = unexpected; 2 =
unresolved/unsent. The CLI never touches the database; run evidence comes
from the API.

## Tests and checks
```sh
./mvnw test     # full backend suite: Phase 1 + outbox/publisher + worker + state machine + run summary proofs, vs real PG + real MQ (current count in VERIFICATION.md)
./scripts/smoke # durable-backend contract still green (roles pinned off for determinism)
```

## Shutdown (non-destructive)
Ctrl-C the API. `./scripts/mq down` and `./scripts/postgres down` stop
containers (volumes keep data). Named resources only: `qdesk-mq`/`qdesk-mqm2`,
`qdesk-postgres`/`qdesk-pgdata`. Never touch host port 5432 or other
projects' containers.

## Gotchas learned (auth 2035)
- The dev image only reads the `mqAppPassword` secret when
  `MQ_CONNAUTH_USE_HTP=true` is set; without it, `app` fails OS-user auth.
- Spring default syntax is `${VAR:default}` (single colon):
  `${VAR:-default}` sends a literal leading dash. That one cost an hour.

## ENV-02 record (verified 2026-10-05)
- Server: IBM MQ 10.0.0.5, image
  `icr.io/ibm-messaging/mq@sha256:378c88e8aee6ff32d6b5fa6ce6584a017b42cec628d785b62dabf417cb3373a2`
  (amd64, runs emulated on arm64 Mac; slower QM boots, fine for the lab)
- Client: `com.ibm.mq:com.ibm.mq.jakarta.client:10.0.0.5` (Jakarta JMS,
  `com.ibm.mq.jakarta.jms` / `com.ibm.msg.client.jakarta.wmq` packages)
- Integration: Spring Boot 4.0.8 `spring-boot-starter-jms`, no IBM Spring
  starter (it targets Boot 4.1.0); explicit `MQConnectionFactory` +
  `CachingConnectionFactory` + transacted `JmsTemplate`
- Terms: IBM MQ Advanced for Developers (container dev license, local lab only)
- Bounds: 2 GB container cap, named volume `qdesk-mqm2`, loopback ports
  1414/9443, persistent queue `QUEUEDESK.INSTRUCTION.IN`

## Learner review (LEARN-02, deferred to final review)
Material for the single end-of-project review (no human gate at phase close):
admit one instruction, find its outbox row and its queue message, then stop
the broker and describe where the work waits and how it resumes. Explain why
the marker write lives in a separate transaction and why a crash there
duplicates delivery instead of losing work. Then trace one eventId through the
outbox row, the queue message (`worker.received` log shows both eventId and
JMSMessageID), the `processed_message` row and the audit trail, and explain
why `@Transactional` on the listener would cover the database only, and why
JMSMessageID must never be the dedup key. Record a short "what I changed
and why" note at the final review.
