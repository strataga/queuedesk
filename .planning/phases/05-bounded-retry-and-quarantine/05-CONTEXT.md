# Phase 5: Bounded retry and quarantine - Context

**Gathered:** 2026-10-04
**Status:** Ready for phase research and planning; execution waits for prerequisites
**Origin:** Synthesized from the supplied PRD, implementation docs and reviewed images. This is source-derived context, not a claimed discuss-phase interview.
**Requirements:** PR06, RETRY-01, QUAR-01, QUAR-02

<domain>
## Phase Boundary

Transient work retries predictably and poison work remains inspectable without a hot loop or silent loss.

Stage: L3. Dependency: Phase 4.
</domain>

<decisions>
## Source Decisions and Planning Defaults

- **D-01:** Three failed processing deliveries is the proposed local maximum; prove the actual chosen provider/listener counting rather than assuming counter equivalence.
- **D-02:** Only one component owns poison routing. Backout queue and queue-manager DLQ serve different purposes.
- **D-03:** BOTHRESH counts failures, not delay; reconnect/backoff and processing pacing must be explicit.
- **D-04:** Quarantine is an operational hold separate from business rejection.
- **D-05:** An MQ move and the DB quarantine projection are not atomic; retain freshness and reconcile observations after DB recovery.

These decisions reflect the PRD and documented precedence resolutions. Version/tool/library choices below remain open until researched; there are no newly asserted locked ADRs.
</decisions>

<specifics>
## Suggested Plan Slices

1. Define failure classification and measured retry/count semantics with pacing outside business transactions.
2. Implement one poison-routing owner with BOTHRESH/BOQNAME and permissions; prove persistent-work preservation on route failure.
3. Add quarantine observations, durable projection identity/reasons, reconciliation and freshness-aware reads.
4. Exercise poison/retry/DB-projection failure cases and write the operator diagnosis runbook.

These are planning inputs, not executable PLAN.md files. Resolve the open implementation choices, then create bounded plans with tasks, verification and must-haves.

## Observable Acceptance

1. Classified transient processing failures use paced bounded retries with documented attempt counts; business rejections are not technical retries.
2. Malformed/unsupported events reach a durable operational hold with stable reason after the configured maximum of three failed processing deliveries.
3. When the backout route is unavailable, work is retained by rollback or consumer stop and an operator can diagnose the routing failure.
4. Quarantine observations reconcile into an API-readable projection whose stale/unavailable state remains explicit during DB outages.

## Evidence

Real MQ poison/backout tests including unavailable route and DB-down projection; preliminary SC10 with retained message evidence and precise attempt counts.
</specifics>

<canonical_refs>
## Canonical References

- `docs/QueueDesk_PRD.md §§8–9, 12, 16–17`
- `docs/images/QueueDesk_Data_Model.png`
- `docs/images/QueueDesk_Status_Model.png`
- `.planning/REQUIREMENTS.md` — requirement definitions and owning phase.
- `.planning/VALIDATION.md` — scenario and failure evidence.
- `.planning/INGEST-CONFLICTS.md` — target versus starter resolutions.
</canonical_refs>

<code_context>
## Existing Code and Integration Points

- `src/main/java/dev/queuedesk/`
- `src/main/resources/`
- `Phase 2 MQ configuration`
- `Phase 3 listener and attempt records`

Future-phase references describe integration points that do not yet exist at intake; re-inspect actual code after dependencies complete.
</code_context>

## Research and Open Implementation Choices

- Use primary IBM/Spring documentation and integration tests to choose provider-owned versus application-owned routing.
- Verify fallback behavior, permissions, bothresh/boqname semantics and how to stop rather than silently discard persistent work when routing is broken.
- Design quarantine identity for a malformed message missing eventId; do not fabricate a valid business instruction.
- Specify projection observation/reconciliation so inspecting the quarantine queue does not inadvertently lose held work.

<deferred>
## Deferred Scope

Replay/retry-now/manual resolution controls remain B01; observing quarantine does not authorize replay.
</deferred>

---
*Phase: 05-bounded-retry-and-quarantine*
*Context gathered: 2026-10-04*
