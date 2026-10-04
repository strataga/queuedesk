# Phase 7: Recovery proof and operations - Context

**Gathered:** 2026-10-04
**Status:** Ready for phase research and planning; execution waits for prerequisites
**Origin:** Synthesized from the supplied PRD, implementation docs and reviewed images. This is source-derived context, not a claimed discuss-phase interview.
**Requirements:** PR09, FAIL-01, FAIL-02, OBS-01, OBS-02, OPS-01, SAFE-01, OPS-02, LEARN-03

<domain>
## Phase Boundary

The operator can demonstrate and diagnose recovery from every specified outage and crash without losing accepted work or inventing completion.

Stage: L3 exit. Dependency: Phase 6.
</domain>

<decisions>
## Source Decisions and Planning Defaults

- **D-01:** Use latches/hooks for specific crash windows; fixed sleeps alone cannot establish recovery evidence.
- **D-02:** Count distinct instructions/events/outcomes and verify no state regression, not merely restarted containers.
- **D-03:** Keep instructionId/eventId/runId in logs/traces, not metric labels.
- **D-04:** Initial guard proposals: halt new generated traffic above 1,000 backlog or below a configurable 10 GB free-disk reserve; treat these as safeguards, not capacity claims.
- **D-05:** Health and observability stay loopback; MQ outages must not trigger liveness restart loops.
- **D-06:** Test artifacts retain seed, fixture/configuration, versions, timestamps and diagnostics. A skipped MQ profile is not a pass.

These decisions reflect the PRD and documented precedence resolutions. Version/tool/library choices below remain open until researched; there are no newly asserted locked ADRs.
</decisions>

<specifics>
## Suggested Plan Slices

1. Add controlled publisher/worker crash hooks and local-only outage/backout controls with bounded diagnostics.
2. Complete SC06–SC10 and regression of all core scenarios, asserting both sides of every commit boundary and original identity.
3. Expose structured logs, operational metrics/read endpoints, liveness/dependency state and resource/backlog stop conditions.
4. Finalize recover/restart/shutdown runbook, CI integration profiles, retained scenario evidence and the learner's explanation checkpoint.

These are planning inputs, not executable PLAN.md files. Resolve the open implementation choices, then create bounded plans with tasks, verification and must-haves.

## Observable Acceptance

1. SC06–SC10 and both publisher/worker crash boundaries recover with stable IDs, retained rows and one definitive result; broken quarantine routing produces no silent drop.
2. The local-only harness uses controlled hooks and bounded eventual assertions, refuses non-lab targets and only operates named QueueDesk resources.
3. Logs/metrics expose backlog age, dedup/failure counts, pending age, quarantine freshness and correctly separated latency, without unbounded ID labels.
4. Resource/capacity guards stop new generated traffic at configured backlog/disk limits; health distinguishes dependency outage from a dead process and preserves restartable pending work.
5. A fresh-reader runbook and recorded demonstration pass the reliability gate; CI explicitly distinguishes real Postgres/MQ passes from skips.

## Evidence

Full failure matrix plus SC01–SC12 regression, real Postgres/MQ results, crash-point snapshots, one-outcome assertions, pending/quarantine freshness, scoped shutdown and learning demonstration.
</specifics>

<canonical_refs>
## Canonical References

- `docs/QueueDesk_PRD.md §§12, 14, 16–17, 19–22`
- `docs/images/QueueDesk_Outbox_Recovery.png`
- `docs/images/QueueDesk_Data_Model.png`
- `docs/VERIFICATION.md`
- `.planning/REQUIREMENTS.md` — requirement definitions and owning phase.
- `.planning/VALIDATION.md` — scenario and failure evidence.
- `.planning/INGEST-CONFLICTS.md` — target versus starter resolutions.
</canonical_refs>

<code_context>
## Existing Code and Integration Points

- `scripts/`
- `src/test/java/dev/queuedesk/`
- `Phase 2 publisher and MQ configuration`
- `Phase 3 worker`
- `Phase 5 quarantine projection`
- `Phase 6 reconciler`

Future-phase references describe integration points that do not yet exist at intake; re-inspect actual code after dependencies complete.
</code_context>

## Research and Open Implementation Choices

- Choose test hooks that are unavailable outside the lab profile and deterministic across process restarts.
- Specify which operational reads/metrics drive traffic guards, including missing/stale guard data behavior.
- Choose minimal local health/metrics exposure, a clear dependency-health model and freshness response fields reusable by Angular.
- Define evidence retention, CI runner resource constraints and documentation paths without requiring paid infrastructure.

<deferred>
## Deferred Scope

Dashboard implementation follows in Phase 8. Cloud and Kubernetes remain optional gated backlog items.
</deferred>

---
*Phase: 07-recovery-proof-and-operations*
*Context gathered: 2026-10-04*
