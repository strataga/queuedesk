# Phase 9: Local Kubernetes deployment - Context

**Gathered:** 2026-10-04
**Status:** Ready for phase research and planning; execution waits for prerequisites
**Origin:** Promoted from BACKLOG.md B03 at user request; local-only scope (kind/k3d/minikube). No cloud authorization is implied.
**Requirements:** K8S-01

<domain>
## Phase Boundary

The backend lab runs on local Kubernetes with working health checks, surviving pod restarts and rollouts without losing durable work or duplicating outcomes.

Stage: Optional deployment. Dependency: Phase 7 (Phase 8 only if already built).
</domain>

<decisions>
## Source Decisions and Planning Defaults

- **D-01:** Local cluster only; no cloud spend, no public exposure. There is no cloud track: B02 was declined as out-of-scope (local-only lab).
- **D-02:** Reuse the Phase 7 health model: liveness means the process is alive, readiness/startup reflect dependency availability.
- **D-03:** One backend workload plus Postgres and MQ with explicit requests/limits; publisher/worker replicas still require the deferred lease/locking design.
- **D-04:** Postgres data lives on a named persistent volume; MQ persistence follows the topology chosen at plan time.
- **D-05:** Teardown is scoped to named QueueDesk resources (namespace or cluster delete) with documented data retention.

These decisions reflect the PRD and documented precedence resolutions. Version/tool/cluster choices below remain open until researched; there are no newly asserted locked ADRs.
</decisions>

<specifics>
## Suggested Plan Slices

1. Choose the local cluster distribution and resource budget; define minimal workload/configuration/secrets/service/storage manifests.
2. Wire liveness/readiness/startup probes to Phase 7 health endpoints; prove dependency outages do not create restart loops.
3. Demonstrate pod restart, rollout/restart and cluster recreation with scenario regression and PVC survival; decide the MQ topology (in-cluster or adjacent lab container).
4. Provide scoped teardown instructions and verify only named lab resources are affected; record retained data handling.

These are planning inputs, not executable PLAN.md files. Resolve the open implementation choices, then create bounded plans with tasks, verification and must-haves.

## Observable Acceptance

1. A local cluster (kind, k3d or minikube) runs the API/publisher/worker with explicit CPU/memory requests and limits inside a documented laptop budget.
2. Liveness/readiness/startup probes use the Phase 7 health endpoints so dependency outages never cause destructive restart loops.
3. Pod restarts, controlled rollout/restart and cluster recreation preserve Postgres durability (named volume) and existing duplicate/recovery guarantees; scenario behavior is unchanged through restarts.
4. MQ runs in a pinned supported topology (in-cluster or adjacent lab container, decided at plan time) with persistent storage; broker restarts recover via the proven outbox path.
5. Scoped teardown (namespace or cluster delete) removes only named QueueDesk resources and documents retained data and remaining cost/exposure (zero for local).

## Evidence

Pod-restart/rollout/storage/teardown checks plus scenario regression through restarts; no cloud spend.
</specifics>

<canonical_refs>
## Canonical References

- `docs/QueueDesk_PRD.md §§17, 20`
- `docs/images/QueueDesk_Architecture.png`
- `.planning/REQUIREMENTS.md` — requirement definitions and owning phase.
- `.planning/VALIDATION.md` — scenario and failure evidence.
- `.planning/INGEST-CONFLICTS.md` — target versus starter resolutions.
- `.planning/BACKLOG.md` — B03 origin of this phase.
</canonical_refs>

<code_context>
## Existing Code and Integration Points

- `Phase 7 health endpoints and operational reads`
- `Phase 2 MQ configuration`
- `Phase 1 migrations`

Future-phase references describe integration points that do not yet exist at intake; re-inspect actual code after dependencies complete.
</code_context>

## Research and Open Implementation Choices

- Choose kind, k3d or minikube against laptop OS/CPU/memory and container-engine availability; record versions and resource budget.
- Decide the MQ topology: in-cluster queue manager versus adjacent lab container, with persistence and restart behavior for each.
- Specify image build/load strategy for the local cluster (no public registry required) and secret handling without values in manifests.
- Define which scenario subset regresses through pod restarts and what "unchanged behavior" asserts (counts, identities, one outcome).

<deferred>
## Deferred Scope

Cloud spend and public exposure are out of scope. Multi-pod replicas need lease/locking design. Dashboard inclusion is optional and only if Phase 8 is already built.
</deferred>

---
*Phase: 09-local-kubernetes-deployment*
*Context gathered: 2026-10-04*
