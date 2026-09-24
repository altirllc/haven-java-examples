# plumb

The Haven connectivity probe. One container that performs a **real read-write
round trip** against every seam a Haven application is wired to, and reports each
one green or red.

Run it first in a new cell or tenant to prove the platform contract before anyone
writes an app against it. Point it at a tenant whose apps are misbehaving to find
out which side of the wire is at fault.

```
identity          ok    tenant acme
postgres.records  ok    schema plumb migrated, round trip verified
postgres.vectors  ok    pgvector 0.8.6 answered a 1024-dimension kNN query
mongodb           ok    database plumb, round trip verified
s3                ok    bucket acme, round trip verified
models.gateway    ok    both aliases are served
identity.zitadel  ok    issuer reachable and publishing 2 signing key(s)
temporal          ok    namespace acme exists (deep mode off)
```

## The one rule

Every other Haven service **fails fast**: a missing database or an unreachable
model gateway kills the process at boot, on purpose. plumb does the opposite, and
that inversion is the design:

- an unset variable makes its probe **SKIPPED** and names the variable;
- a broken seam makes its probe **FAIL** and the app stays up to say so;
- **readiness never depends on a seam** — plumb must stay in service precisely
  when something is down, or the tenant loses the only thing that could explain
  the outage.

It follows from there that there is no `spring-boot-starter-data-*` on the
classpath, no `@NotBlank` on any seam property, and every client is built lazily
inside the probe that owns it. See `CLAUDE.md` before changing any of that.

## What each probe proves

Each writes and reads back wherever the seam allows it — opening a connection
proves the network and nothing an application depends on.

| Probe | What it actually does |
|---|---|
| `identity` | tenant id from the pod label `haven.tenant`, profile, JVM, host |
| `postgres.records` | connect → advisory lock → `CREATE SCHEMA` → Liquibase → insert, select, delete |
| `postgres.vectors` | the vectors **database** (not schema) → `vector` extension → 1024-dim kNN query |
| `mongodb` | ping → insert, find, delete with the tenant credential |
| `s3` | bucket exists → put, get, list, delete under `plumb/` (path-style addressing) |
| `temporal` | `DescribeNamespace` and its retention. With `DEEP_TEMPORAL_PROBE=true`, also a real workflow → activity → echo on task queue `plumb` |
| `models.gateway` | `GET /v1/models`: the key is accepted and both aliases are served. With `DEEP_LITELLM_PROBE=true`, also a chat completion → embedding, asserting the 1024 width |
| `identity.zitadel` | OIDC discovery → the issuer agrees with its own name → JWKS is non-empty |
| `sibling.*` | each configured app answers in-cluster; a host that does not resolve is **skipped**, not red |

## Surfaces

| | |
|---|---|
| `GET /` | the page — server-rendered, no JavaScript, no build step |
| `GET /api/checks` | last known results; runs nothing, safe to poll |
| `POST /api/checks/run` | run everything now |
| `GET`/`POST` `/api/checks/{id}[/run]` | one probe |
| `GET /actuator/prometheus` | `haven_probe_up{seam}` — 1 ok, 0 failed, **NaN when not configured** |
| `GET /actuator/health` | liveness and readiness, neither gated on a seam |

The sweep also runs itself every `PLUMB_INTERVAL` (default 60s).

## Running it

### Against the containers on rc-server (the default here)

Local Docker is not used on this workstation; the stack lives on the LAN box.

```bash
ssh rc-server "cd C:\Users\reconnect\haven-plumb; docker compose up -d"
./mvnw spring-boot:run -Drun.profiles=rc     # or run PlumbApplication from the IDE
```

`application-rc.yaml` carries the addresses. Ports are remapped there because
rc-server already serves PostgreSQL 16 and MongoDB 8.0 natively on the defaults —
`.env.example` has the matching values, and `.env` on that host already does.

> **Docker on rc-server over SSH:** everything works except `docker pull`.
> Docker Desktop calls `docker-credential-desktop`, which needs an interactive
> logon session that an SSH session does not have. Pull images from the console
> session, or register a one-shot scheduled task running as the logged-on user.

### Everything on one machine

```bash
docker compose up -d
./mvnw spring-boot:run        # the `local` profile is the default
```

### The model gateway

Off by default, because it is the one seam that needs a credential:

```bash
cp .env.example .env          # set OPENAI_API_KEY
docker compose --profile models up -d
DEEP_LITELLM_PROBE=true \
  MODELS_GATEWAY_ENDPOINT=http://localhost:4000 MODELS_GATEWAY_API_KEY=sk-plumb-local ./mvnw spring-boot:run
```

`DEEP_LITELLM_PROBE=true` is the point of running LiteLLM locally at all: without
it the probe stops at the alias list and never calls a model. In a cell it stays
off, because the sweep runs every 60s against a metered vendor.

LiteLLM is the same software the platform runs in `haven-models`, configured with
the same two **aliases** — `chat-with-haven` and `text-embedding-1024` — so the
contract is identical to a cell and only the upstream behind the alias differs.

## Build

```bash
./mvnw verify                 # compiles and runs the fail-open contract tests
./mvnw -DskipTests package
```

JDK 25. Tests need no Docker.

## Deploying it

`bundle/` is the Kustomize bundle: one Deployment, one Service, one IngressRoute
at `/plumb`, and a `service.json` marked `visible: false` — plumb is an operator
tool, not something a tenant browses for.

```bash
kubectl kustomize bundle      # render it
haven bundle publish --tenant <id>
```

`bundle/deployment.yaml` is worth reading even if you never deploy plumb: it is
the complete, working env wiring a Java app receives in a tenant vCluster, and
plumb exercises every line of it. Copy it when wiring a new app — but note that
every `secretKeyRef` there is `optional: true`, which is right for a probe and
wrong for everything else.

## The environment contract

What the bundle injects, where it comes from, and which probe covers it.

| Variable | Source | Probe |
|---|---|---|
| `HAVEN_TENANT_ID` | pod label `haven.tenant` (downward API) | `identity` |
| `POSTGRES_USERNAME` / `_PASSWORD` | `tenant-postgres-secret` | records, vectors |
| `POSTGRES_RECORDS_DATABASE` / `_VECTORS_DATABASE` | `tenant-postgres-secret` | records, vectors |
| `POSTGRES_DATABASE_URL` / `POSTGRES_VECTORS_URL` | composed in the bundle → `postgres:5432` | records, vectors |
| `MONGODB_USERNAME` / `_PASSWORD` | `tenant-mongodb-secret` | `mongodb` |
| `MONGODB_URI` | composed in the bundle → `mongodb:27017` | `mongodb` |
| `STORAGE_S3_ENDPOINT` / `_ACCESS_KEY` / `_SECRET_KEY` / `_BUCKET` | `tenant-s3-secret` | `s3` |
| `TEMPORAL_ADDRESS` | `workflows-temporal.default:7233` | `temporal` |
| `TEMPORAL_NAMESPACE` | pod label `haven.tenant` — one namespace per tenant | `temporal` |
| `TEMPORAL_TASK_QUEUE` | the app's own name | `temporal` |
| `DEEP_TEMPORAL_PROBE` | run a real workflow, not just a namespace lookup — **false** | `temporal` |
| `MODELS_GATEWAY_ENDPOINT` / `_API_KEY` | `tenant-models-secret` | `models.gateway` |
| `MODELS_CHAT_MODEL` / `_EMBEDDING_MODEL` | gateway **aliases**, not vendor models | `models.gateway` |
| `DEEP_LITELLM_PROBE` | spend a real inference per sweep — **false** | `models.gateway` |
| `ZITADEL_ISSUER` / `ZITADEL_MCP_AUDIENCE` | `tenant-zitadel-secret` | `identity.zitadel` |

plumb's own settings: `PLUMB_INTERVAL` (60s), `PLUMB_PROBE_TIMEOUT` (20s),
`PLUMB_STARTUP_DELAY` (5s), `POSTGRES_SCHEMA` (plumb), `MONGODB_COLLECTION`
(plumb), `STORAGE_S3_PREFIX` (plumb/).

> The platform docs in `haven-docs` are behind the code on several of these —
> they still name `postgres-secret`, `haven-azure-credentials` and `models-vllm`.
> The table above and `bundle/deployment.yaml` were read off the live scaffold and
> catalog bundles, and plumb proves them by running.
