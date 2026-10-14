# Phase 8: Read-only Angular dashboard - Context

**Gathered:** 2026-10-04
**Status:** Ready for phase research and planning; execution waits for prerequisites
**Origin:** Synthesized from the supplied PRD, implementation docs and reviewed images. This is source-derived context, not a claimed discuss-phase interview.
**Requirements:** PR11, UI-01, UI-02, UI-03, UI-04, UI-05, UI-06

<domain>
## Phase Boundary

A local operator can inspect runs, instructions and recovery evidence in an accessible dashboard that states what is current, stale or unknown.

Stage: L4 optional. Dependency: Phase 7.
</domain>

<decisions>
## Source Decisions and Planning Defaults

- **D-01:** Use latest Angular plus Tailwind (user decision 2026-10-04; PRD default was Angular) and the backend API as the only source; no direct DB/MQ access. Pin exact Angular/Node/Tailwind versions when Phase 8 is planned.
- **D-02:** Five-second polling pauses when the view is not visible.
- **D-03:** Always display the simulated nature of the application; combine text/icons with status colors.
- **D-04:** Transport evidence is separate from business result. Missing intermediate observations are not invented timeline facts.
- **D-05:** Replay, retry-now, cancel, edit, manual entry and manual resolution are absent from this read-only phase.
- **D-06:** Backend L2 and L3 evidence gates must pass before Angular execution.

These decisions reflect the PRD and documented precedence resolutions. Version/tool/library choices below remain open until researched; there are no newly asserted locked ADRs.
</decisions>

<specifics>
## Suggested Plan Slices

1. Define the UI contract and Angular structure after backend gates; add bounded API read models for aggregates, time filters, run metadata and timelines.
2. Implement overview and instruction filtering/pagination with scoped freshness and consistent status/currency presentation.
3. Implement instruction timeline, run accounting and operational views using only the API.
4. Verify hidden-tab polling, loading/empty/stale/error states, keyboard access and no manufactured completion; update local UI runbook.

These are planning inputs, not executable PLAN.md files. Resolve the open implementation choices, then create bounded plans with tasks, verification and must-haves.

## Observable Acceptance

1. Overview cards and an instruction list show scoped counts, amounts, age and run/status/time filters with bounded pagination and last-refresh timestamps.
2. Instruction detail shows immutable input, IDs, reason and an ordered business/transport timeline, including completed outcomes with lagging publish markers.
3. Run detail accounts for expected unique, admitted, completed, rejected, repeated and unresolved work from API-backed run evidence.
4. Operational screens show backlog, MQ availability and quarantine freshness; five-second polling stops while hidden and loading/empty/unavailable/stale states remain distinct.
5. Keyboard operation, text/icon status labels, integer-based currency formatting and a permanent simulated label work throughout the read-only experience.

## Evidence

Read API integration checks and browser checks for all five views, bounded filtering/pagination, stale/unavailable semantics, visibility-aware polling, keyboard access and integer currency.
</specifics>

<canonical_refs>
## Canonical References

- `docs/QueueDesk_PRD.md §§4, 6–7, 15–17, 20`
- `docs/images/QueueDesk_Architecture.png`
- `docs/images/QueueDesk_Status_Model.png`
- `docs/images/ui/01-overview.png` through `10-scenario-run-detail.png` (L4 screens are design inputs; 04/05 are B04 scope)
- `.planning/REQUIREMENTS.md` — requirement definitions and owning phase.
- `.planning/VALIDATION.md` — scenario and failure evidence.
- `.planning/INGEST-CONFLICTS.md` — target versus starter resolutions.
</canonical_refs>

<code_context>
## Existing Code and Integration Points

- `src/main/java/dev/queuedesk/http/InstructionController.java`
- `Phase 4 run reports/metadata`
- `Phase 7 operational reads`
- `No Angular app exists at intake`

Future-phase references describe integration points that do not yet exist at intake; re-inspect actual code after dependencies complete.
</code_context>

## Research and Open Implementation Choices

- Verify the latest supported Angular/Node/Tailwind toolchain when this phase is planned and pin exact versions then; do not guess versions during intake.
- Use docs/images/ui/01-overview, 02-payments, 03-payment-detail, 06-queues, 07-telemetry, 08-trace-detail, 09-scenarios and 10-scenario-run-detail as L4 layout/navigation/typography inputs; mockups 04-risk-review, 05-risk-assessment and the RS05 catalog entry are B04 (L5) scope, not Phase 8.
- Define snapshot scope/freshness and timestamp ordering for aggregate/run/timeline endpoints, including stale projections.
- Decide how bounded run manifests from Phase 4 are read consistently without exposing fault injection or operational write controls.

<deferred>
## Deferred Scope

Any operator action belongs to B01 with identity/outcome re-check, authorization, explicit confirmation and audit. Deployment beyond the laptop is out of scope; local Kubernetes is Phase 9.
</deferred>

---
*Phase: 08-read-only-angular-dashboard*
*Context gathered: 2026-10-04*
