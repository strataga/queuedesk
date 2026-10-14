# QueueDesk dashboard runbook (L4 read-only UI)

Local Angular + Tailwind dashboard for inspecting runs, instructions and recovery
evidence. Read-only by construction: the app issues GETs only (no POST/PUT/DELETE
surface exists in its API client), and every figure names its scope and freshness.

Pinned toolchain (Phase 8): Angular 22.2.1, Tailwind CSS 4.3.3, Node 24.21.0
(`dashboard/.nvmrc`). Loopback only: the dev server binds 127.0.0.1:4200 and
proxies `/api` + `/actuator` to the lab backend on 127.0.0.1:8080.

## Start

```sh
./scripts/postgres up && ./scripts/mq up   # lab dependencies (once per boot)
unset MQ_APP_PASSWORD; ./mvnw spring-boot:run   # backend on 127.0.0.1:8080
./scripts/dashboard install   # first checkout only (npm install)
./scripts/dashboard serve     # UI on http://127.0.0.1:4200/
```

Open http://127.0.0.1:4200/ in a browser. Stop the UI with Ctrl-C; stop the
backend the same way, then `./scripts/reset-lab` if you want a clean lab.

`./scripts/dashboard build` produces a production bundle under
`dashboard/dist/`; `./scripts/dashboard test` runs the vitest unit suite
(API client, polling, pipes, shell).

## Views

- **Overview** (`/`): pick a run id for scoped accounting (admitted, completed,
  unresolved, outbox) plus lab-wide safeguards (liveness, readiness,
  quarantine, backlog) and a lab-wide risk panel (held, review-required,
  not-assessed, gate mode). Recent runs are remembered in the browser only.
  Recent instructions carry a risk column; run accounting accumulates a
  client-side "since page open" trend; an illustrative instruction-path strip
  shows where the risk gate sits (before simulated clearing).
- **Payments** (`/payments`): search/run/status/risk filters plus
  since/until/page-size with bounded cursor paging, a scope-aware census row
  (run accounting when a run id is set, otherwise the lab-wide overview
  census inside the KPI window — the caption always names the scope),
  client-side search and risk narrowing over loaded rows only (labeled as
  such — the list API has no server search or risk filter), and a Pending
  risk badge for RECEIVED rows still awaiting worker-side evaluation while
  the gate is on. A reconciliation panel reports pending confirmations
  exactly when the sample exhausts the cursor, "at least N" otherwise. The
  first page auto-refreshes; paging deeper pauses auto-refresh (the page
  says so) and caps loaded rows at 500.
- **Payment detail** (`/payments/:id`): stat cards (amount, risk,
  delivery, admission), Timeline/Request/Audit tabs (the audit tab shows
  durable business transitions; transport attempts render separately below),
  a reconciliation panel for unknown confirmations, a risk card linking to
  the assessment for held instructions, and copyable identifiers.
  Unknown ids report "no such instruction", never a guess.
- **Run detail** (`/runs/:runId`): expected/admitted/completed/rejected/
  duplicate/unresolved accounting (unresolved includes risk-held).
  Expected-unique and duplicate counts are honestly **unknown** (the
  generator persists no manifest; repeats reuse the existing row), with the
  reason shown. A needs-attention panel links pending confirmations and
  risk-held work to their filtered lists.
- **Risk review** (`/risk`): read-only held queue with a window selector,
  held/review-required/not-assessed counts, server-side search plus
  assessment/reason filters, and cursor paging. An OFF gate explains
  itself; review actions are deferred, so the page links to assessments
  and audit trails only.
- **Risk assessment** (`/risk/assessments/:instructionId`): one held
  instruction's frozen features, matched deterministic rules, typed
  signals from the mock or live Jev provider (confidence always quoted with
  the backend's own "never the probability of fraud" note), evaluation
  timeline and frozen versions.
  Rules-only assessments honestly show no signals; unsampled traces
  link nowhere. Unknown ids report "no such assessment".
- **Operations** (`/ops`): liveness vs readiness (db + MQ components),
  safeguard state, quarantine list with heartbeat freshness, and lab-wide
  meters including risk holds and provider calls by result/status.
  Unrecorded series read "no reading", never zero.
- **Queues** (`/queues`): outbox/MQ/quarantine freshness. MQ depth is labeled
  an application estimate; a stale quarantine projection shows its last
  observation with the current count unavailable, never zero. The backlog
  chart accumulates client-side samples and says "since page open".
- **Telemetry** (`/telemetry`): admission/completion/provider p95 cards with
  window notes, client-side latency trends (since page open), sampled traces
  (bounded IDs + timings, no raw payloads) and exporter health.
- **Trace detail** (`/telemetry/traces/:traceId`): sampled waterfall with real
  `queuedesk.*` span names, selectable spans, async links and an audit-trail
  link. Unknown or evicted trace ids report "no such trace".
- **Scenarios** (`/scenarios`): catalog with search, recent run reports, and
  a command preview rendered verbatim from the backend's real CLI template
  (copy button never re-derives flags). The UI executes nothing.
- **Scenario run** (`/scenarios/runs/:runId`): totals, assertions
  (pass/fail distinct), generator config, reproducibility preview and a JSON
  report export. Unknown run ids report zeros, never 404.

## Reading freshness

Every panel carries a "Last refresh" timestamp. Polling runs every 5 seconds
while the tab is visible and stops when hidden (no background requests, no
overlap). When a refresh fails, the last good reading stays on screen behind a
"Showing last good reading" banner; when nothing ever succeeded, the panel says
"Backend unavailable". Loading, empty, error and unavailable states are visually
and textually distinct throughout.

## Status presentation

Business states (including "◉ Risk held"), delivery markers, health,
freshness and risk outputs (ALLOW/REVIEW/NOT_ASSESSED/REJECT) each render as a
text-plus-marker pill (e.g. "✔ Completed (simulated)"), so meaning never depends
on color alone. All controls are keyboard reachable with visible focus; motion
honors `prefers-reduced-motion`. Amounts format from integer minor units without
floating point. The SIMULATION banner is permanent: synthetic data only, no real
money or participants.
