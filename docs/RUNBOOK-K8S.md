# QueueDesk local Kubernetes runbook (Phase 9, kind)

The backend lab on a local kind cluster: one `queuedesk-app` replica (API,
publisher, worker, quarantiner, reconciler) plus in-cluster Postgres 17. IBM MQ
stays the adjacent lab container `qdesk-mq`, reached over the shared `kind`
docker network by container IP — see "Why MQ is adjacent" below. Local only:
no registry, no cloud, no published ports; all access is `kubectl port-forward`
on loopback.

Pinned toolchain: kind 0.33.0, kindest/node v1.37.0, kubectl client v1.36.1,
`postgres:17`, app image `qdesk-app:k8s` (built locally, `kind load`).

## Resource budget (documented laptop budget)

Inside Docker Desktop (8 GB on this machine); single node, one replica each:

| Workload | Requests | Limits | Notes |
|---|---|---|---|
| kind control-plane | ~1 CPU / 1 GB | — | Fixed by kind; etcd + API + kubelet |
| postgres | 250m / 256Mi | 1 CPU / 512Mi | Matches host-lab `--memory=512m` |
| queuedesk-app | 500m / 768Mi | 2 CPU / 1536Mi | JVM `-Xmx1024m` inside the limit |
| wait-for-postgres init | 50m / 32Mi | 250m / 128Mi | Exits before the app starts |

Total requests ≈ 1.75 CPU / 2 GB; limits ≈ 4 CPU / 3 GB plus the node. The
adjacent `qdesk-mq` keeps its host-lab 2 GB cap. Multi-pod app replicas are
out of scope: publisher/worker scaling needs the deferred lease/locking
design, so the Deployments use `Recreate` with one replica.

## Lifecycle

```sh
./scripts/k8s up       # cluster + images + secrets + manifests + waits
./scripts/k8s setup    # MQ queues/grants on the adjacent broker (additive, safe to repeat)
./scripts/k8s status   # clusters, pods, PVCs, services, QM status
./scripts/k8s-verify   # full gate: scenarios + restarts + outage + recreation (exit 0 = PASS)
./scripts/k8s teardown # delete namespace + cluster; lab data retained in .k8s-data/
./scripts/k8s wipe-data # destroy retained .k8s-data/ (refuses while the cluster exists)
```

Secrets are created at deploy time from lab defaults
(`QD_DB_PASSWORD` or `queuedesk`; `.cache/mq-secrets/mqAppPassword` or
`queuedesk-lab`) — no secret values live in `k8s/`. The MQ endpoint travels in
a generated `qdesk-mq-endpoint` ConfigMap holding the broker's container IP on
the `kind` network; re-run `up` if `qdesk-mq` is ever recreated.

Read the API through a forward (the scripts use 18080; nothing is NodePorted):

```sh
kubectl -n queuedesk-lab port-forward svc/queuedesk-app 18080:8080 &
curl http://127.0.0.1:18080/api/v1/runs/<runId>
```

Note: a service forward sticks to one backing pod and exits when that pod is
replaced — re-establish it after pod deletes/rollouts (`scripts/k8s-verify`
does this automatically).

## Health model

Probes reuse the Phase 7 endpoints: liveness is
`/actuator/health/liveness` (process alive), readiness/startup are
`/actuator/health/readiness` (db + mq reachable). An MQ outage therefore marks
the pod NotReady without restarting it — proven in `k8s-verify` stage 5
(NotReady + liveness UP + 0 restarts across a broker stop/start, then outbox
recovery). A `wait-for-postgres` initContainer absorbs the startup race so
fresh deploys start with 0 restarts; MQ is deliberately not waited on, since
starting into NotReady and recovering is the behavior under test.

## Durability and teardown scope

Postgres data lives on a hostPath PV backed by the kind extraMount
(`.k8s-data/pgdata` on the operator machine, PV reclaim Retain), so `kind
delete cluster` + `up` serves pre-recreation rows — proven in stage 6, which
byte-compares a run summary across recreation. Teardown removes exactly the
`queuedesk-lab` namespace and the `qdesk` cluster; `qdesk-postgres`,
`qdesk-mq` and `.k8s-data/` are untouched (verified: both lab containers kept
running with the QM `Running`). Retained data: Postgres rows under
`.k8s-data/` until `wipe-data`; MQ objects/ grants persist in the adjacent
`qdesk-mqm2` volume as in the host lab. Remaining cost/exposure: zero (local
only, no registry, no cloud).

## Why MQ is adjacent

IBM MQ publishes no linux/arm64 image (manifest list: amd64, ppc64le, s390x
only), and kind on this arm64 machine cannot schedule it: kubelet refuses the
pull ("no match for platform"), `kind load` fails on the manifest-list
layout, and even a node-cached copy is invisible to CRI (`ctr` lists it,
`crictl` does not), so pods stay in ImagePullBackOff/ErrImageNeverPull. The
adjacent topology is the acceptance-listed alternative: the proven lab broker
with its persistent volume, loopback publishing untouched (PRD §17), reached
from pods over the internal bridge network. Broker restarts recover through
the same proven outbox path as the host lab (SC06 semantics, stage 5).
