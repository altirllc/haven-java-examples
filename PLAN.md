# Plan — `plumb` and `loom`

Two Java applications on Haven. `plumb` proves the platform contract works; `loom` builds
on top of Anvil and notifies through the foundation notifications stack.

Written 2026-09-15. Status: awaiting go-ahead.

---

## 0. Ground truth this plan is built on

Verified by reading the repos, not the docs — `haven-docs` is behind the code (it still
names `postgres-secret` / `haven-azure-credentials` / `models-vllm`, has no page for the
Java scaffold, and no page for `slate` / `nudge` / `dispatch`).

**The Haven contract for a Java app** (`haven-scaffold/java/agent/bundle/deployment.yaml`):

| Seam | Wiring |
|---|---|
| Records DB | `tenant-postgres-secret` → `jdbc:postgresql://postgres:5432/$(POSTGRES_RECORDS_DATABASE)` |
| Vectors DB | same secret → `POSTGRES_VECTORS_DATABASE`, pgvector |
| MongoDB | `tenant-mongodb-secret` → `mongodb://…@mongodb:27017/<app>?authSource=admin` |
| S3 | `tenant-s3-secret` (SeaweedFS, `storage-s3.default:8333`) |
| Temporal | `workflows-temporal.default:7233`, namespace = tenant, task queue = app name |
| Models | `tenant-models-secret` → LiteLLM gateway, aliases `chat-with-haven`, `text-embedding-1024` |
| Identity | `tenant-zitadel-secret` → `ZITADEL_ISSUER`, `ZITADEL_MCP_AUDIENCE` (optional) |
| Tenant id | pod label `haven.tenant` via downward API → `HAVEN_TENANT_ID` |
| Ports | api 3000, mcp 3001, ui 8080, jobs management 3002 |

**The dispatch stack** (`haven-catalog/bundles/dispatch`) is our own foundation stack:
`fn-events-broker` (:8001) + `events-api` (:8080) + `fn-notifications` (:8089), all on
Mongo, `DEFAULT_TENANT_ENABLED=true`, tenant id `default`. `fn-events` and
`events-gateway` are frozen — the live line is `events-api` 2.x and `events-broker` 1.2.1+.

**Toolchain**: JDK 25 at `G:\TOOLS\jdk-25.0.1`; scaffold is Spring Boot 4.1 / Spring AI 2.0
/ Temporal 1.37; Maven wrapper ships in the scaffold. Target is `docker compose` locally,
then GitHub Actions → GHCR (the scaffold already ships those workflows).

**Constraints that shape everything below**

- `haven-system`, `haven-tenants`, `haven-operators` are not available. Anything that
  needs the control plane (minting a Zitadel service account, publishing to a tenant
  catalog, `haven` CLI) is deferred and flagged, not guessed.
- Git is read-only here: files get created and edited, never committed or pushed.
- No changes to Anvil, no changes to the platform, no new entries in the shared catalog.

---

## 1. `plumb` — the connectivity probe

**One sentence**: a single-container Spring Boot app that performs a real read-write
round trip against every platform seam and reports green/red per seam.

**Why it is not the agent scaffold**: the scaffold is eight modules, a React UI, and it
*fail-fasts* on Postgres, Mongo, Temporal and the model gateway. A diagnostic that refuses
to boot when something is broken is useless. `plumb` is the deliberate inversion.

**Same stack as the scaffold anyway** (Boot 4.1 / JDK 25 / the scaffold's Dockerfile and
CI shape) — a green `plumb` on a different stack would not de-risk `loom`.

### Layout

```
haven-plumb/
  backend/pom.xml                 one module
    src/main/java/haven/plumb/
      PlumbApplication.java
      config/                     @ConfigurationProperties records — validated, never fatal
      probe/Probe, ProbeRegistry, ProbeResult
      probe/impl/                 one class per seam
      temporal/                   PingWorkflow + PingActivity + in-process worker
      web/ProbeController         GET /api/checks, /api/checks/{id}, POST /api/checks/run
      web/PageController          GET / — one server-rendered page, no JS build
      health/, metrics/
  Dockerfile
  bundle/                         kustomize, single container, visible:false
  .github/workflows/build.yml     → ghcr.io/altirllc/plumb
  docker-compose.yml              pgvector, mongo, temporal(+ui), seaweedfs, litellm
  README.md  CLAUDE.md
```

### The probes

Each returns `{id, group, status: ok|fail|skipped, detail, latencyMs, evidence}`.

| # | Probe | What it actually does |
|---|---|---|
| 1 | `identity` | `HAVEN_TENANT_ID` from the pod label, active profile, build version |
| 2 | `postgres.records` | connect → `CREATE SCHEMA IF NOT EXISTS plumb` under an advisory lock → Liquibase → insert/select/delete a heartbeat row |
| 3 | `postgres.vectors` | connect to the vectors DB → assert `vector` extension → create table, insert a 1024-dim vector, kNN query, drop |
| 4 | `mongodb` | ping → insert/find/delete in the `plumb` collection |
| 5 | `temporal` | `DescribeNamespace`, then start `plumb-ping-{uuid}` → activity → echo. Proves worker registration on task queue `plumb`, not just a socket |
| 6 | `s3` | put/get/delete `plumb/heartbeat-{ts}.txt`, list the prefix |
| 7 | `models.gateway` | `GET /v1/models` → one chat completion on `chat-with-haven` → one embedding on `text-embedding-1024`, asserting dimension 1024 |
| 8 | `identity.zitadel` | fetch `${ZITADEL_ISSUER}/.well-known/openid-configuration`, assert the JWKS URL resolves |
| 9 | `siblings` | configurable in-cluster URLs, one row each — `anvil-api.default:3000/health`, `signal-api.default:3000/api/capabilities`, `dispatch` events `/events/health`, notifications `/notifications/actuator/health` |
| 10 | `self.metrics` | `/actuator/prometheus` renders and carries our own gauges |

### Rules it must keep (goes in CLAUDE.md so nobody "fixes" it)

- **Fail-open.** Missing env → the probe reports `skipped` and *names the variable*. It is
  never a boot failure. This is the opposite of the scaffold and it is deliberate.
- **Write, then read back.** A connect-only check proves too little.
- **Everything namespaced and cleaned up** — schema `plumb`, S3 prefix `plumb/`, workflow
  id prefix `plumb-`, Mongo collection `plumb`.
- **Readiness is self-only.** A seam probe must never flap the pod out of the load balancer.
- Probes run at startup (async, after readiness), on `PLUMB_INTERVAL` (default 60s), and
  on demand.

### Steps

| | Deliverable | How it is verified |
|---|---|---|
| P1 | Repo skeleton, Maven, boots, `/` page and `/api/checks` with everything `skipped` | `mvnw verify`, open the page |
| P2 | `docker-compose.yml` + probes 1–6 green locally | all six rows green |
| P3 | LiteLLM in compose + probe 7; probe 8 (`skipped` locally) | chat + embedding round trip, dim 1024 |
| P4 | Probe 9 against a locally running dispatch stack and Anvil | sibling rows green |
| P5 | Metrics, health, ECS logging, hardened container (non-root, read-only rootfs) | `/actuator/prometheus` carries the gauges |
| P6 | Dockerfile, GH Actions → GHCR, `bundle/` kustomize (`visible: false`) | image builds; `kustomize build` clean |
| P7 | README + CLAUDE.md documenting the env contract | — |

Secondary value: P7 is the executable documentation of the Java env contract, which the
platform docs currently get wrong.

---

## 2. `loom` — a supervisor built on top of Anvil

**Positioning**: `loom` *builds on* Anvil. It does not modify Anvil and adds nothing to the
platform. Anvil triages items autonomously; `loom` watches what Anvil decided and does what
Anvil deliberately does not.

**What it does**

- Pulls Anvil's items and audit trail (`/api/items`, `/api/items/:id/logs`,
  `/api/agent/activity`).
- Keeps its own durable **case** per item — a `processCase` Temporal workflow with SLA
  timers, signals, and a 7-day horizon.
- Runs its own Spring AI agent with a **different mandate** from Anvil's triager: a second
  opinion on Anvil's decisions, drift detection ("flagged three days ago, nobody looked"),
  and cross-item pattern recall through pgvector memory.
- **Acts back on Anvil** through Anvil's own MCP tools (`approve_item` / `reject_item` /
  `flag_item`), so the action lands on Anvil's normal signal path — the same one a human
  uses.
- **Notifies through `fn-notifications`**: each case transition publishes a domain event
  via `events-api`; a tenant rule maps it to `NOTIFICATION` / `SEND_AND_FORGET_NOTIFICATION`
  on `notification-input-topic`; `fn-notifications` renders the template and delivers.

The story reads cleanly: *Anvil decides, `loom` supervises and tells people.* It exercises
the agent, Temporal, pgvector, MCP-as-a-client and the events/notifications platform
without touching anything we do not own.

**Base**: `haven new agent --template java/agent`, `hello` → `loom`. Keeps the eight-module
determinism fence, the MCP server, plan metering, the Mongo wiring and the React UI. The
scaffold's Signal escalate is **replaced** by the `fn-notifications` path (same fail-open
ergonomics: a capability pre-flight, `features.notify` on `/api/me`, the button always live).

### The four seams to pin — and the risk in each

1. **Auth into Anvil.** Anvil's REST requires edge identity headers
   (`X-Auth-Request-User/Email/Roles`), which only the tenant gateway sets; an in-cluster
   call bypasses the edge and is rejected (`identity.ts` returns an empty subject in
   production, `requireRole` throws 403). Its MCP requires a Zitadel JWT whose org equals
   the tenant and whose audience is the tenant MCP client. **Locally Anvil is not in
   production mode and mints a dev identity, so local works with no token.** For a cell we
   need a Zitadel service account, which lives in `haven-operators` / `haven-system` — not
   available. Plan: build against MCP with a bearer token supplied by config, empty
   locally; flag the service-account question for when access exists. **Forging the edge
   headers is not on the table.**
2. **Notification wiring.** `fn-notifications` needs, per tenant: the seeded `CREATE_RULE`
   bootstrap rule (the chicken-and-egg rule that lets a tenant create rules at all), then a
   rule mapping `loom`'s external event type → `NOTIFICATION` /
   `SEND_AND_FORGET_NOTIFICATION` → `notification-input-topic`, plus
   category / subcategory / template / recipient preferences, plus a channel that works
   without vendor credentials. Email needs SES, SMS needs Twilio, push needs Firebase —
   **in-app is the candidate for a credential-free demo.**
3. **Events client on Boot 4.** `co.altir.events.api:events-api-client-spring` 2.1.5 is
   built against Boot 3.5 / Java 21; `loom` is Boot 4.1 / JDK 25. **This is the single
   biggest technical risk in task 2.** Fallbacks, in order of preference: (a) it just
   works; (b) talk to the broker over plain HTTP from `loom`; (c) pin `loom` to Boot 3.5 /
   JDK 21.
4. **Model gateway locally.** `loom` refuses to boot without a reachable OpenAI-compatible
   gateway serving `chat-with-haven` and `text-embedding-1024`. Plan: a LiteLLM container
   in compose mapping those two aliases onto whatever provider key is available. The
   production contract stays byte-identical.

### Steps

| | Phase | Deliverable |
|---|---|---|
| **L0** | **Spikes — before any app code** | (a) dispatch stack up locally (`events-api/docker/compose` + `fn-notifications`), one notification delivered end to end **by hand**; (b) `events-api-client-spring` on Boot 4.1 — compiles and connects; (c) Anvil up locally, `/api/items` reachable from a plain HTTP client. Output: a findings note and the decisions it forces. **Checkpoint — the design may change here.** |
| L1 | Scaffold | Render, rename, `mvnw verify` green on JDK 25 (fence + ArchUnit + Testcontainers) |
| L2 | Domain | `Item` → `Case` (`anvilItemId`, state, SLA, last-seen decision, review outcome), Liquibase, `CaseService`, usage metering kept |
| L3 | Ingest | `AnvilClient` (JDK HttpClient, lives in `api` — the fence bans HTTP clients in `workflows`); polling activity; `processCase` workflow with SLA timers |
| L4 | Agent | Tools swapped to `get-case`, `list-cases`, `review-decision` (act), `sla-status`; supervisor system prompt; pgvector memory kept |
| L5 | Act back | A dispute asks Anvil to flag the item, from an activity. **Over REST, not MCP** — see the note below |
| L6 | Notifications | `Notifier` publishing to `events-api`; idempotent bootstrap of rule + template + category/subcategory + recipient prefs; capability pre-flight; the audit row carries the notification id |
| L7 | UI | Case board, inherited agent dashboard + chat, and a "what Anvil decided vs what `loom` said" view |
| L8 | Surfaces | `loom`'s own MCP tools; plan + feature toggles in `service.json` |
| L9 | Delivery | Dockerfiles, GH Actions → GHCR, `bundle/` kustomize with the `publisher` sentinel |
| L10 | The demo | One `docker-compose.yml` bringing up the whole story: postgres+pgvector, mongo, temporal(+ui), seaweedfs, litellm, broker, events-api, fn-notifications, Anvil (three GHCR images), `loom` api/jobs/ui. Delivered as a `demo` profile on loom's existing compose, plus **a stand-in edge** — see the note below |

### The demo needs a fake edge, and the reason is a platform gap

L10 brings both apps up together, which means loom has to call Anvil for real —
and in a cell that call is made *through the tenant edge*, which strips the
`/{app}` prefix and attaches the caller's identity as `X-Auth-Request-*`
headers. Anvil trusts those headers because the edge is supposed to be its only
ingress.

There is no supported way for loom to authenticate to Anvil in-cluster today
(see `haven/SECURITY-edge-header-trust.md`), and loom deliberately does not
forge the headers — doing so would be building on the hole rather than around
it. So the demo runs an `edge` nginx that does the edge's two jobs and
authenticates nobody.

This is worth being blunt about in both directions. It makes the demo run on one
machine with loom's code unchanged and honest. It also means the demo does
**not** demonstrate that the Anvil integration works in a cell — it cannot,
until an app has a way to prove who it is to another app.

### Deviation: the write-back went over REST, not MCP

L0-C concluded that MCP was the right path into Anvil because it is the only one
of the two that can carry a credential. Building it in L5 turned out to be the
wrong trade, for a reason the spike could not have known:

- The **reads** can only be done over REST. MCP exposes no tool that returns the
  triager's rationale, and the rationale is the thing loom reasons about — so
  loom talks REST regardless.
- The blocker is **authentication, not protocol**. Neither path works in a cell
  without a Zitadel service account, so adding MCP unblocks nothing today.
- An MCP client could not be tested against anything real, and shipping an
  unverifiable protocol implementation to sit behind a credential that does not
  exist is speculative work.

So the write-back is one method on `AnvilClient` (`flagItem`). If a service
account ever exists, moving the WRITES to MCP is a contained change — that one
method — while the reads stay where they are.

### Out of scope, explicitly

No edits to Anvil. No platform changes. No PR to the shared `haven-catalog`. No Signal.

---

## 3. Cross-cutting

- **Git** — read-only. Files get written; commits, branches, pushes and GitHub repo
  creation are yours.
- **Location** — `G:\CODE\altir\foundation\haven\haven-plumb` and `…\haven-loom`, next to
  the other Haven repos. App names `plumb` and `loom` satisfy the CLI's naming rule
  (lowercase letters and digits, no dashes); images land as `ghcr.io/altirllc/plumb` and
  `ghcr.io/altirllc/loom-{api,jobs,ui}`, matching the `nudge` convention.
- **Docs** — each repo gets `README.md` and `CLAUDE.md` in the scaffold's house style.
  Claims get grounded in source, not in `haven-docs`.
- **Verification** — every phase ends in something runnable: `mvnw verify` green,
  `docker compose up` green, an endpoint or page showing the expected state. Failures get
  reported with their output.

## 4. Sequencing

`plumb` first (P1 → P7). It is self-contained, it proves the JDK 25 + compose + GHCR path
end to end, and it leaves behind both a working local stack and a diagnostic to point at
`loom` when `loom` misbehaves. Then `loom` L0.

## 5. Assumptions being made — say the word if any is wrong

1. `plumb` runs one container with an **in-process Temporal worker** (a deviation from the
   api/jobs split, justified: proving the round trip needs a worker).
2. Local models go through a **LiteLLM container** in compose rather than pointing Spring AI
   straight at a vendor — it keeps the production contract identical.
3. The demo notification channel is **in-app** (no vendor credentials). Real email/SMS/push
   gets wired only if credentials exist.
4. `loom` talks to Anvil through **MCP**, with the bearer token left empty locally.
