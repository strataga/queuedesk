# Bounded learning milestones

This starter implements SK01 and SK04's foundation behavior and rehearses SK03 retrieval. It does **not** satisfy SK02's database commit guarantee, SK05's database migration runbook, or full L1 restart survival. The source PRD is authoritative for future product acceptance; this checklist records implementation status without changing that specification.

| Stage | Current evidence / next gate |
|---|---|
| L0 foundation | Plain record validation and tests; deterministic dry-run Java CLI; finite loopback submissions. Explain records, exceptions, interfaces and collections. |
| Pre-L1 HTTP rehearsal (today) | Controller/service/repository with map adapter; 202/200/400/404/409/503; GET; single-JVM synchronization. Memory is lost on restart. No durable admission. |
| L1 durable skeleton (next) | Add one local Postgres, migration, BIGINT amount and unique idempotency key; transaction + semantic fingerprint; enforce 16 KiB JSON limit and pagination; prove invalid input creates no rows and commit failure never yields 202; prove restart survival. |
| L2 MQ core MVP | Add durable outbox, stable event identity, IBM MQ publisher and one consumer, mock clearing and receiving-bank adapters. Separate HTTP admission, delivery detail and simulated business outcome. Complete normal/duplicate/conflict/invalid/burst reports. |
| L3 reliability | Bound retry/backoff; poison quarantine; durable consumer dedup; DB/JMS crash tests; PENDING_CONFIRMATION and reconciliation after lost reply. Never blindly reissue an uncertain payment instruction. |
| L4 optional | Read-only Angular once backend evidence is understandable; then explicitly authorized deployment exercises. |

No real MQ, Postgres, Angular, failure lab or cloud work is included today. The sender is synthetic generator input; mock clearing and receiving-bank interfaces are deferred until L2. No COMPLETED_SIMULATED response is invented.

## First exercise

Add two boundary assertions for 1 and 1,000,000,000 minor units. Change the upper bound temporarily to make a test fail; read the exception and restore it. Then ask: what makes identical records equal, and why is correlation excluded from that comparison?

## First persistence exercise

Keep the HTTP controller thin. Implement another repository with an explicit database transaction and unique constraint; adapt service admission to concurrency-safe database semantics. Preserve existing IDs on exact repeats. A Java synchronized block does not protect multiple processes or a database.

L1 gate: submit one instruction, stop only the API, restart, GET the same ID, and show it remains RECEIVED. Do not proceed to MQ until you can explain durable admission without business completion.
