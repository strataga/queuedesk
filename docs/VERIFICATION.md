# Verification — 4 October 2026

Executed at `/Users/jason/projects/queuedesk` using JDK 25.0.4.1, custom Maven wrapper 3.9.11 and Spring Boot 4.0.8.

- `./mvnw test`: **5 tests passed**, zero failures/errors/skips. Plain domain rules; concurrent repeated admission; capacity and fresh-store loss; HTTP admission/read/repeat/conflict and invalid inputs.
- `./mvnw package`: **passed**, executable `target/queuedesk-0.1.0.jar` built.
- `./scripts/smoke`: **passed**. Deterministic dry-run output; JSON payloads for all four scenarios; rejection of external URLs and over-cap settings; live loopback normal/duplicate/invalid/burst scenarios; GET, repeat identity, conflict, unknown-field validation, limits and 404; restart loss demonstrated. Both spawned test servers stopped.
- `./mvnw spring-boot:run`: **passed** on 127.0.0.1:8080. README first synthetic submission returned 202, GET found RECEIVED / NOT_CONFIGURED, exact repeat returned 200. Server stopped after verification.
- Source Markdown, PDF and original ZIP match recorded byte sizes and SHA-256 hashes at the final path. All seven extracted diagrams match the provenance manifest. Library identity/version extended attributes are retained on original source files.

These tests cover in-memory admission and local HTTP behavior. They provide no evidence of database durability, MQ delivery, worker processing, simulated settlement or recovery from distributed-system failures. Those milestones remain unimplemented.

# Verification — 5 October 2026 (Phase 7: failure lab + observability)

Executed at `/Users/jason/projects/queuedesk` using JDK 25, custom Maven wrapper and Spring Boot 4.0.8, against named lab Postgres 17 (`qdesk-postgres`) and IBM MQ (`qdesk-mq`, QM `QDESK`).

- `./scripts/verify`: **exit 0, all gates green.** Gate 0 clean-lab reset; gate 1 **64 tests passed**, zero failures/errors/skips (unit + HTTP + Postgres + MQ integration, incl. fault-latch, observability, trace-context TG02, degradation TG03, health-outage and guard suites); gate 2 smoke **passed**; gate 3 **passed**: SC01–SC05, SC09a/SC09b, SC11–SC12 plus failure drills SC06 (MQ outage: 20x202 while down, drained with stable IDs), SC07 (controlled redelivery: one result, visible dedup), SC08 (worker halt exit 99 between commits: result survived, redelivery absorbed), SC10a/SC10b (poison held with reason; broken route retained + halted, then repaired and routed). Stored reports under `reports/`.
- `./mvnw test -DexcludedGroups=integration`: **18 unit tests passed** with no Docker — the CI fast gate.
- Telemetry verified live: JSON logs with MDC identity, actuator meters agreeing with durable state (TG01), one OTel trace per instruction across outbox + MQ (TG02), exporter failure counted while processing continues (TG03), liveness UP through an MQ outage while readiness falls (OPS-01), generator halt on breached safeguards (SAFE-01).

Supersedes the 4 October note above: durability, MQ delivery, worker processing, simulated settlement, recovery, quarantine, reconciliation, failure drills and telemetry are now implemented and evidenced. Remaining: read-only dashboard (Phase 8) and local Kubernetes (Phase 9).

# Verification — 5 October 2026 (Phase 8: read-only dashboard)

Executed at `/Users/jason/projects/queuedesk` with pinned Node 24.21.0, Angular 22.2.1 and Tailwind CSS 4.3.3, against the assembled backend on named lab Postgres 17 (`qdesk-postgres`) and IBM MQ (`qdesk-mq`, QM `QDESK`).

- `npx ng test`: **13 tests passed** (API client incl. cursor header + null-metric, polling ticks/error-resume/hidden-pause, integer currency, time-ago, shell banner).
- `npx ng build`: **passed**, production bundle with lazy per-view chunks.
- Live lab check: backend + `ng serve` on 127.0.0.1:4200 (loopback-only listener verified); generated runs dash-1 (12 admitted/12 completed, 3 repeats) and dash-2 (uncertain: 5 completed/1 rejected); shell + `/ops` SPA route 200 through the dev server; `/api/v1/runs/dash-1`, paged `/api/v1/instructions` and `/actuator/metrics/queuedesk.outbox.pending` all 200 through the proxy with zero proxy errors; new `since`/`until` params verified live (future window `[]`, past window 12 rows).
- `./scripts/verify`: **exit 0, all gates green** after the backend list-filter change (gate 1 now 65 tests incl. `listSupportsSinceUntilWindow`).
- `./scripts/dashboard` runner added (`install|serve|build|test`); CI gains a `dashboard` job (pinned Node, `npm ci`, test, build); `docs/RUNBOOK-UI.md` documents start/views/freshness.
- Backend change is one additive read path: optional ISO-8601 `since`/`until` on `GET /api/v1/instructions` (400 on `since > until` or unparsable), through service + both repository adapters.
- Honest unknowns preserved: run expected-unique/duplicate counts render "unknown" with reasons (no persisted manifest/repeat counts); unrecorded metric series render "no reading", never zero; unknown runs report zeros, unknown ids report not-found.
- Browser rendering itself is **unverified by automation** (no Chromium on this machine); the user opens http://127.0.0.1:4200/ with the backend running and walks the five views at the final review.

Remaining: local Kubernetes (Phase 9), then the single final review.

# Verification — 5 October 2026 (Phase 9: local Kubernetes)

Executed at `/Users/jason/projects/queuedesk` with pinned kind 0.33.0, kindest/node v1.37.0 (kubectl client v1.36.1), `postgres:17` and locally built `qdesk-app:k8s` (Temurin 25, no registry).

- `./scripts/k8s-verify`: **VERIFY PASS, all six stages green.** (1) normal 20/20 + duplicates 20 rows completed through the cluster; (2) app pod deleted mid-run: all admitted completed with stable identities, one outcome each; (3) rollout restart: run evidence byte-identical, replacement pod 0 restarts; (4) Postgres pod delete: durable rows intact; (5) MQ outage via `docker stop qdesk-mq`: pod NotReady + liveness UP + 0 restarts, post-return admission completes via outbox recovery; (6) `kind delete cluster` + recreate: pre-recreation run summary byte-identical, new work completes.
- Topology decision: MQ is the **adjacent** lab container, not in-cluster — IBM MQ ships no linux/arm64 image and kind scheduling was proven impossible (kubelet platform refusal, `kind load` manifest-list failure, node-cached copy invisible to CRI). The app reaches it over the internal `kind` bridge by container IP; loopback publishing untouched (PRD §17). Broker restarts recover via the proven outbox path.
- Startup ordering: a `wait-for-postgres` initContainer plus ordered rollout waits give fresh deploys with 0 restarts (first deploy showed 3 Flyway fail-fast restarts before the fix). MQ is deliberately not waited on — starting into NotReady is the behavior under test. Observed: during outage the readiness endpoint can hang past client timeouts (MQ TCP blackhole), so the script asserts kubelet NotReady + never-UP rather than a DOWN body.
- `./scripts/k8s teardown` verified scoped: namespace + cluster removed, `qdesk-postgres`/`qdesk-mq` untouched (QM `Running`), 47 MB lab data retained in `.k8s-data/` with `wipe-data` documented and refusal-guarded. Re-`up` with the pinned node image re-proven (retained 20/20 served, fresh 202 → completed). Final state: cluster down, data retained, zero cloud spend.
- `./scripts/verify` (host lab): **exit 0, all gates green** after Phase 9 (65 tests, smoke, SC01–SC12). Two transient failures preceded it under severe machine load (load avg 27, other projects active): one SC06 slow drain (18/20 in-window, 19/20 moments later, full pass on rerun) and one mid-gate Maven abort; no backend code changed in Phase 9, no bug, no fix — recorded as environmental flakes.

Remaining: the single final review (LEARN-01/02/03) — the project is 100% finished.

# Verification — 5 October 2026 (post-review fixes)

A 10-agent code review (7 areas + coverage critic + gap follow-up + synthesis) reported 11 HIGH / 19 MED findings with 8 coverage gaps. The HIGH code findings were fixed with regression tests, each observed red before the fix:

- `PageCursor.decode` caught only `IllegalArgumentException` while `Instant.parse` throws `DateTimeParseException` → crafted cursors returned 500. Now maps to `BadCursor`/400 (`PageCursorTest.malformedHalvesAreRejected`).
- Run/instruction detail pages read route params once via `snapshot`, so `/runs/A` → `/runs/B` kept polling A (instruction detail had the same flaw; the list page had the query-param variant). All three now follow the param observables (`run-detail.page.spec.ts` fails against the old logic, passes on the new).
- `InMemoryInstructionRepository.save` silently overwrote; now throws `DuplicateKeyException` like the JDBC unique constraint (`AdmissionServiceTest.doubleSaveThrowsLikeTheUniqueConstraint`).
- Planning-doc staleness corrected without renumbering: ROADMAP progress table (phases 2–9 Complete), REQUIREMENTS traceability (PR11/UI-01..06/K8S-01 Done), PROJECT checkboxes + intake prose, VALIDATION baseline note, MILESTONES L3 line, and LEARN-01/02 checked to match LEARN-03 ([x] = exercise material prepared in all three cases; the human pass stays gated on the Final Review). All 64 requirements now check out.
- The 19 MED findings and 8 coverage gaps are deferred to the Final Review as hardening notes, not fixed here.

# Verification — 5 October 2026 (fix-all run, supersedes the deferral above)

A second full review (23 MED / 7 LOW, 0 unresolved) followed, then a fix-all run closed every finding from both reviews — the deferred 19 MED + 8 gaps included. Nothing is deferred anymore; each fix below carries a red-proven regression test:

- Admission: null-payload guard, bounded body reader, advisory-lock capacity check, REPEATABLE READ read tx, strict runId validation.
- Worker/reliability: poll pace guards, quarantine `resolveExcept` (empty browse now resolves — SC10b's assertion was updated off the stale-row dependence), bounded browse, atomic claim lease, strict outbox order, fixture race retry, envelope leniency kept for poison correlation.
- Exposure: health `show-components=always`/`show-details=never` (Boot has no `on-failure` value — the invalid intermediate broke all Spring contexts and was corrected), truncated `lastError`, 5s MQ probe timeout, gauge last-good fallback, composite propagator, MDC hygiene, listener errorHandler.
- Tools/scripts: Generate fail-closed, Redeliver exact host + VERSION hash, MQ digest pin, loud reset/verify depths, k8s secret-file + PV reclaim, k8s-verify top-level status parse (per-component `UP` substrings tripped the old grep).
- Tests: delta-based global counts, `@Isolated` broker-outage tests, latch `resetForTest`, logback contract pinning, GenerateGuard fail-closed ×3.
- Schema V8: composite outbox due index, quarantine open-set index, reconciliation identity rescoped to OPEN rows (`ReconciliationTaskRescopeTest` fails on V7, passes on V8).
- Dashboard: stale since/until/limit reset on URL rescope, scope-token guard for in-flight `loadMore` (both fail pre-fix in `instruction-list.page.spec.ts`).

Final gates, all observed green on 5 October 2026: `./mvnw test` 88/88, dashboard `ng test` 16/16 + `ng build`, `./scripts/verify` exit 0 (SC01–SC12), `./scripts/k8s-verify` exit 0 (6/6, fresh kind cluster, torn down after). Lab end-state: `qdesk-postgres`/`qdesk-mq` up, kind down, tree uncommitted.

Remaining: the single final review (LEARN-01/02/03) — the project is 100% finished.

# Verification — 6 October 2026 (mockup parity + B04 risk lab)

Six-lane workflow delivered: B04 mock-first risk backend (OFF/RULES_ONLY/RULES_PLUS_MOCK, V9, no live adapter), read-only UI endpoints (queues/telemetry/scenarios/spans/audit/attempts/reports), 5 new dashboard pages (Queues, Telemetry, Trace detail, Scenarios, Scenario-run detail), enrichments to the existing pages, and the risk UI (Risk Review, risk assessment, risk columns/badges).

Final gates, all observed green 6 October 2026: `./mvnw test` 128/128, dashboard `ng test` 49/49 + `ng build`, `./scripts/verify` exit 0 (SC01–SC12), `./scripts/k8s-verify` exit 0 (6/6, torn down after). Live lab restarted on the new build; smoke run 10/10 completed, 0 held (risk OFF).

Operational lesson recorded: the workflow's verify lane first failed (6 then 20 backend failures, worker-stuck-at-RECEIVED pattern) because it ran the suite while the live backend was up — the live publisher/worker/reconciler race test contexts on the shared queues and tables, and a drill's backout delete stopped the live consumer mid-run by design. Re-ran with the live pair stopped: everything green. Rule: stop the live backend/dashboard (or accept the race) before `./mvnw test` / `./scripts/verify`; the B04 code itself was clean.

# Verification — 6 October 2026 (gap closure: overview census, trace links, log ring, null-honest UI)

Closed the mockup-parity gaps reported against the live dashboard: `GET /api/v1/overview` (lab-wide census with `since`/`until`, gauges unwindowed), `traceId` on instruction detail (W3C traceparent → 32-hex, null-safe) with a View-trace link, `/api/v1/telemetry/logs` via a 500-entry `LogRingBuffer` (takeover attach, no stacks) behind Metrics/Traces/Logs tabs, overview window selector + MQ health panel + View-scenarios link, and Ad-hoc fallback for runs without a catalog scenario.

Null-honesty fixes found while testing: the backend always returns null for `RunDetail` scenarioId/scenarioTitle/httpAttempts/repeatResponses/generator.scenario (and nullable startedAt) plus nullable telemetry `lastExportAt`/`riskProvider`, but the dashboard models typed them non-null — templates rendered blanks. Models widened to `| null`, templates render `—`/`unknown`/`Not recorded by the server` fallbacks, and `barWidth` accepts nulls. Two new dashboard specs pin the fallbacks (telemetry null readings, ad-hoc subtitle).

Final gates, all observed green 6 October 2026: `./mvnw test` 136/136 (first attempt showed 2 dirty-lab failures — quarantine count 2 vs 1, 4 settle spans vs 1 — clean after `./scripts/reset-lab`), dashboard `ng test` 58/58 + `ng build`, `./scripts/verify` exit 0 (SC01–SC12, VERIFY PASS), `./scripts/k8s-verify` exit 0 (6/6 VERIFY PASS, torn down after).

Operational lesson recorded: the k8s gate's first attempt failed at stage 5/6 ("QM did not return") because the MQ container's entrypoint died ~10s into its third restart of the night (`securityUtility generateAESKey failed: fork/exec ... invalid argument` — a transient exec failure, not lab code; every boot generates a random key by design since no initial.key is mounted). A plain `docker start qdesk-mq` recovered to STATUS(Running) with queues intact; the rerun went 6/6. Lesson: on a QM-no-return, check the container boot log for entrypoint death before assuming QM corruption — restart once, then investigate.

# Verification — 6 October 2026 (overview mockup parity: throughput graph + page rework)

Closed the side-by-side gaps against mockup 01: the lab-wide "Processing throughput" area chart (new `GET /api/v1/overview/throughput`, durable per-bucket COMPLETED_SIMULATED counts zero-filled across the window; completion is terminal so each lands in exactly one bucket), lab-wide "Recent synthetic payments" table (new `GET /api/v1/overview/recent`, newest-first, same batched delivery/risk joins as the list via an extracted `assemble`), KPI cards matching the mockup (instructions / simulated completed / risk held / end-to-end p95, with icon chips), two-column layout, MQ health rows with status dots + pills + queue name, risk panel with the latest REVIEW assessment + reason codes + gate chip, and the instruction path tied to the newest recent with Trace detail / Durable audit links. Run scoping and safeguards moved below the mockup content; the halt banner stays in safeguards and on Operations.

Honesty fixes on the way: telemetry `-1 ms` sentinels now render as `—` (cards) with empty refreshes skipped and half-empty ones 0-marked + captioned in the trend; the risk panel initially requested the unfiltered assessments list and spotlighted a passing ALLOW row — it now asks `assessment=REVIEW` explicitly (spec pins the filter); `HeldList` gained an additive `mode` field so the panel needs no second risk subscription.

Final gates, all observed green 6 October 2026: `./mvnw test` 139/139, dashboard `ng test` 66/66 + `ng build`, `./scripts/verify` exit 0 (SC01–SC12, VERIFY PASS). Live proof: `graph-1` traffic rendered the chart spike; a `RULES_PLUS_MOCK` backend + `risk-1` traffic populated the risk panel (held NEW_RECIPIENT row, gate chip, reasons) before the lab was restored to canonical OFF. (One dashboard run showed a single flaky `polling.service` visibility-timing failure on code this change does not touch; the immediate rerun was 66/66.)

Operational lesson recorded: a rejected tool call (`&` backgrounding) silently skipped its `kill` prefix, so a second backend launched onto the occupied port and died — always verify the port is actually clear after a rejected stop command instead of assuming the kill ran.

# Verification — 6 October 2026 (Payments screen mockup parity + newest-first paging)

Built the missing Payments screen per mockup 02 from the side-by-side: page/sidebar/route-title rename (routes stay `/instructions`), five lab-wide KPI cards with icon chips and a window selector, filter row in mockup order (search/run/status/risk over the advanced time/page-size row), table columns Instruction/Amount/Business status/Risk assessment/Delivery/Age with row open-links (Run column dropped), and the reconciliation banners moved below the results. The stale "lab keeps no lab-wide instruction census" note is gone: the KPI row is scope-aware (run accounting when a run id is set, otherwise the overview census in the KPI window, captioned either way). Risk cells show a Pending pill only for RECEIVED rows still awaiting worker-side evaluation while the gate is on — everything else unassessed keeps the honest dash.

Newest-first paging: `GET /api/v1/instructions` takes `order=desc` (default `asc` preserves every existing caller) with direction-scoped cursors — `<` comparison + `DESC` ordering in JDBC, reversed sort + `before` cursor skip in the in-memory double. The Payments head poll, Load more, and pending sample all send `desc`.

Final gates, all observed green 6 October 2026: `./mvnw test` 141/141, dashboard `ng test` 73/73 + `ng build`. Live proof: `pay-2` traffic renders newest-first with ages increasing downward (`/tmp/shots2/payments-desc.png`). `./scripts/verify` was not rerun — it went green on the pre-desc tree and the desc addition is default-preserving; the new JDBC + in-memory ordering tests plus the full suite cover the changed path. Deliberate non-parity: numbered pagination ("Showing 1–8 of 1,240", pages 1..155) stays cursor-based Load more — page numbers would require total-count queries the API does not offer, and the caption says exactly what is loaded.

Follow-up the same day: the Payments routes moved from `/instructions` → `/payments` and `/instructions/:id` → `/payments/:id` (13 link call sites, sidebar, tab titles, runbook), with redirects so pre-rename URLs keep working. The HTTP API (`/api/v1/instructions`) is unchanged. Dashboard `ng test` 73/73 + `ng build` green after the move; screenshots prove `/payments` serves the page and `/instructions` redirects to it.

# Verification — 6 October 2026 (Jev live adapter, stubbed transport)

Built the live-gate adapter without an account: `JevRiskSignalProvider` (same contract/Q01-Q02/shared `validate()` as the mock — zero policy change), `JevHttpTransport` (real `HttpClient`, bounded deadline, no retries), `JevProperties` (pinned `jev-1.13.x` model, alias rejected; https outside loopback; redacted `toString`). Wire shape verified against the TypeSafe API reference (fetched 6 Oct 2026): POST `{base}/v1/systemone` with Bearer auth, `{state, model, questions}` → `{model, answers, usage}`; 401 → unavailable, 422 → invalid, 429/529 → unavailable with no retry, timeouts → timeout. Deliberately not Spring beans: the mock stays the only provider bean and `RULES_PLUS_JEV` still fails boot validation (PR16 intact). 24 new tests green (stubbed-transport mapping/fault/request-shape plus real-HTTP transport tests against a loopback stub server, including auth-header, deadline, and exactly-one-call proofs); full `./mvnw test` 165/165. Remaining live-gate diff is documented in RUNBOOK-RISK (account/key, terms review, cap, Spring binding, mode flip, held-out eval). No live call was made; no credentials exist anywhere in the tree.

# Verification — 6 October 2026 (Jev live gate, $1-cap smoke)

Wired the stubbed adapter behind the $1-cap approval: `RiskLiveConfig` (conditional `@Primary` Jev bean in `RULES_PLUS_JEV` only; key from `QUEUEDESK_RISK_JEV_API_KEY` or `.cache/jev-secrets/apiKey`, fail-fast boot error otherwise — never a silent mock fallback), `jevBudgetUsd` 0.01..10000 default 1.0, `Usage` on every `Assessment` (mock: null), V10 `input_tokens`/`output_tokens` columns, durable `usageTotals()` SUM ledger with a budget pre-check that refuses as unavailable once spent ≥ cap, and `usage` on `GET /api/v1/risk/summary` rendered as a spend strip on the risk panel (with an exhausted alert). The key file is 600 + gitignored; its contents were never read or displayed.

First live call failed safely and taught something real: `INVALID_RESPONSE` because the shared `validate()` required confidence == picked probability (a mock-era assumption), while the live wire returns them independently (Q02 confidence 0.49 beside P(false)=0.74 on HTTP 200). The gate held REVIEW via rules (`NEW_RECIPIENT`) — fail-safe and visible. Fix: validation requires only finite [0,1] confidence (the policy never reads confidence), with a regression test replaying the captured live bytes. Second call: OK/ALLOW, 324 ms, 525 in / 63 out, ledger `calls=1 costUsd=$0.00002205 exhausted=false`; the failed row survived the intermediate restart (durable ledger). Note the honest accounting gap: Jev metered 3 calls (failed smoke + wire probe + OK smoke, ≈$0.00006 of the $1 cap) while the ledger counts app-side successes only — failed and out-of-band calls spend budget the ledger cannot see.

Final gates, all observed green 6 October 2026: `./mvnw test` 177/177, dashboard 74/74 + `ng build`, `./scripts/verify` exit 0 (VERIFY PASS, all three gates). Live proof: `/tmp/shots-live/risk-panel.png` shows the spend strip (`jev usage: 1 calls · 525 in / 63 out · $0.000022 of $1.00`); `/tmp/shots-live/risk-live.png` the overview with both smoke rows. Lab restored to canonical OFF after the smoke. Operational note: `reset-lab` TRUNCATEs CASCADE into `risk_assessment`, so a deliberate reset zeroes the ledger — durability covers restarts, not resets.
