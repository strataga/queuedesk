# Deferred Feature Chapters

These items preserve all later source scope. They are roadmap candidates with concrete prerequisites, work packages and acceptance, not active implementation phases. Promote them with GSD into the next numbered phase when their entry conditions are met.

## B01 — Controlled replay and manual resolution

**Source:** PR12; docs/QueueDesk_PRD.md §§4, 15, 22.
**Depends on:** Phase 7 reliability and trustworthy instruction/quarantine state; Phase 8 if actions are delivered through the dashboard.
**Entry gate:** Separate action/authority/audit design defining allowed operations, identity rules and access control. The current local UI remains read-only.

**Work packages:**

1. Specify per-action semantics for technical replay, retry-now, cancel, edit and manual resolution. State explicitly which actions are valid at each business/delivery state; do not assume every listed idea will be permitted.
2. Identify one exact instruction or held event, show its last known state, re-check original receiver outcome where uncertain, and distinguish replay of existing intent from a new business request.
3. Require reason and explicit confirmation; implement appropriate actor authorization and immutable audit recording of actor/time/old state/new state/correlation.
4. Test stale selection, concurrent changes, repeated commands, known terminal outcomes, unknown outcomes, denied authority and audit failure.

**Acceptance:** No bulk automatic replay of pending work; original logical identity is preserved for technical replay; no second simulated effect; denied/stale actions make no state change; every permitted action is auditable. An edit that would change business intent cannot masquerade as an idempotent replay.

## B02 — Optional AWS and Terraform learning (DECLINED)

**Status:** Declined on 2026-10-04: user confirmed local-only lab with no deployment plans. Section retained for provenance; DEPLOY-01 moved to out-of-scope.
**Source:** DEPLOY-01; docs/QueueDesk_PRD.md §§1, 17, 20.
**Depends on:** Phase 7 local reliability and a functioning local shutdown/recovery runbook.
**Entry gate:** Explicit sandbox account/resource authorization, a concrete learning goal, cost ceiling, private-resource design, IAM/security review and destroy plan.

**Work packages:**

1. Choose the smallest approved hosting topology and budget alarms after checking current support, licensing and costs.
2. Define Terraform state handling, least-privilege credentials, secrets management, private networking, persistent storage and limited health exposure.
3. Reproduce local acceptance scenarios in the sandbox and verify deployment/restart/rollback boundaries.
4. Test named-resource teardown and confirm retained data/state handling and remaining cost exposure.

**Acceptance:** Authorized private sandbox only; bounded spend and clear ownership; reproducible setup; no real payment data; working operational checks; teardown evidence. Authentication/TLS and least-privilege review are mandatory before any nonlocal exposure.

## B03 — Optional Kubernetes learning (PROMOTED to Phase 9)

**Status:** Promoted to roadmap Phase 9 (local kind/k3d/minikube) on 2026-10-04 at user request. Local-only scope needs no B02 cloud prerequisite; the packages below are superseded by the Phase 9 context.
**Source:** K8S-01; docs/QueueDesk_PRD.md §20.
**Depends on:** A successful simpler hosting exercise (B02 or an explicitly chosen simpler alternative) and Phase 7 health/recovery behavior.
**Entry gate:** A specific learning objective, chosen local/sandbox environment and explicit resource budget.

**Work packages:**

1. Define minimal workload/configuration/secrets/service/storage manifests and resource requests/limits.
2. Verify liveness/readiness/startup behavior so dependency outages do not create destructive restart loops.
3. Demonstrate controlled rollout/restart, persistent-volume behavior and existing duplicate/recovery guarantees.
4. Provide scoped shutdown/teardown instructions and verify only named lab resources are affected.

**Acceptance:** Existing scenario behavior remains correct through pod restarts; persistent data survives intended lifecycle events; resources stay within budget and health semantics are understood.

## B04 — Optional Jev fraud risk lab (L5)

**Source:** PR13, PR14, PR15, PR16, RS01–RS06, TG04; docs/QueueDesk_PRD.md §§15, 17A, 23–25; docs/images/QueueDesk_Risk_Gate.png; docs/images/ui/04-risk-review.png; docs/images/ui/05-risk-assessment.png.
**Depends on:** Phase 7 worker/reliability path for the pre-clearing gate; Phase 8 dashboard for the read-only risk panel. If Phase 8 is skipped, the backend lab may proceed alone and the risk panel waits for a dashboard host.
**Entry gate:** Explicit L5 selection after the core MQ flow works. Mock-first: RULES_ONLY baseline and RULES_PLUS_MOCK learning complete before any live adapter is considered. Live RULES_PLUS_JEV additionally requires account/credential setup, terms/retention review, a pinned model, call/cost caps and re-evaluation on model change.

**Work packages:**

1. Build versioned Java feature snapshots (amount, velocity, elapsed time, receiver history) with frozen as-of timestamps and explicit missingness; exclude retries/duplicates from velocity.
2. Define the RiskSignalProvider application contract (assessmentId, inputHash, versions, typed signals, latency, evaluationStatus) with Java-first validation; implement OFF/RULES_ONLY/RULES_PLUS_MOCK modes with a mock provider.
3. Implement Java policy composition (ALLOW/REVIEW/NOT_ASSESSED/REJECT) with predefined reasons; commit RISK_HELD plus the handled-event marker atomically before clearing; never call the receiver on hold.
4. Build the evaluator-only held-out harness (disjoint seeds/histories, frozen final set) and run RS01–RS06; add the read-only risk panel (assessment status, reason text, versions, last assessment time) with review actions still deferred.
5. Apply §24 mechanics: never hold a database transaction open during provider HTTP; guard concurrent provider calls with a durable attempt lease; reuse completed assessments under frozen versions with no silent re-score; resume audited reviews through a new deduplicated continuation event on the original instructionId.

**Acceptance:** PR13–PR16 and RS01–RS06 pass locally with reproducible reports; TG04 separates operational latency from offline accuracy; no live call by default; provider failure holds safely and never silently changes modes; no replacement instruction is ever created.

## Design Options Requiring a Future Decision

- Multi-currency scale/conversion: deferred in PRD §7; requires a new amount/currency contract.
- Multiple publishers/workers: PRD §10 requires leases/locking/concurrency/crash design before replicas.
- Separate API/publisher/worker process profiles: allowed later in PRD §5, not required microservices.
- Alternative generator language: PRD allows it if the exact CLI/safety/report contract stays fixed; current Java default serves the learning goal.
- Multi-user/public product: outside current scope; would require new authorization/access/security requirements.

Real banking integrations, actual money movement, ledger/settlement claims and production compliance remain excluded, not backlog commitments.
