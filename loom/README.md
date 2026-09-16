# loom

A supervisor for another app's automated decisions — a Haven agentic app in
Java, with a REST API, an autonomous agent daemon on Temporal, an authenticated
MCP server and a React UI.

Anvil's triage agent decides what happens to work items. loom opens a **case**
for each one, gives it a deadline, and has its own agent judge whether Anvil's
decision was defensible — agreeing, disputing (which flags the item back to
Anvil so a human sees it), or escalating. A case nobody settles before its
deadline **lapses**, and both escalation and lapse raise a notification through
Dispatch. The point is the failure nobody notices: an automated decision that
was wrong, or one that was never made at all.

```
Anvil ──poll──▶ ingest ──▶ case (deadline running)
                              │
                         agent reviews
                              │
        ┌─────────────────────┼─────────────────────┐
      agree                dispute                escalate
     (closed)        flag it back to Anvil      tell a human
                        (still watching)             │
                              │                      │
                          deadline passes with no verdict → lapsed → tell a human
```

## State

| | |
|---|---|
| Domain, persistence, workflows, ingest, agent, push-back, notifications | built, 77 tests green |
| Notification path | **verified end to end** against a live Dispatch stack |
| Agent's verdicts | **unverified** — needs a model gateway key; loom will not boot without one |
| Anvil integration | **unverified in a cell** — Anvil resolves callers from edge headers, and loom refuses to forge them. Works locally against Anvil run from source |

`L0-FINDINGS.md` records what the spikes established and what building on them
taught, including the parts that are blocked and why.

## Requirements

- **JDK 25** (LTS)
- **Docker** — for the local stack and the integration tests

Maven comes from the wrapper; there is nothing else to install.

### Building without a local Docker

The only test that needs Docker is `CaseServiceTest` (Testcontainers: Postgres +
Mongo). Everything else — the enforcer fence, the ArchUnit determinism rules and
the other 77 tests — runs without it:

```bash
backend/mvnw -f backend/pom.xml verify -Dsurefire.excludes='**/CaseServiceTest.java'
```

### The one dependency that is not on Maven Central

The Dispatch client (`co.altir.events.api:events-api-client-spring`) comes from
Altir's Artifactory, so a clean machine needs credentials:

```bash
backend/mvnw -f backend/pom.xml -s ~/.m2/settings-altir.xml verify
```

CI supplies the same file from `secrets.MAVEN_SETTINGS`, and the jobs image
takes it as a BuildKit secret rather than a build arg — an arg would be recorded
in the image history.

The full suite runs in CI, where Docker is available. If the containers run on
another host, the compose ports are all overridable — copy `.env.example` to
`.env`.

## See the whole thing run

One command brings up Anvil, loom, the model gateway, Dispatch and a stand-in
edge, so you can watch a decision travel end to end without writing any of it:

```bash
cp .env.example .env        # then set OPENAI_API_KEY
docker compose --profile demo up -d --wait
```

Then open **http://localhost:8088/loom/** — and Anvil, the app being supervised,
at **/anvil/**. Both are behind one port because in a cell both are behind one
edge. (8088 is the default; `.env.example` ships rc-server's remapping, where
that port is taken.)

What to watch, in the order it happens:

1. Create an item in Anvil. Its triage agent decides what to do with it —
   roughly every 30s here, 60s in a cell.
2. loom's ingest notices the item and opens a **case** with a 10-minute
   deadline (a cell uses 24 hours; the demo is compressed so a case can lapse
   while you are still looking at it).
3. loom's agent reads Anvil's decision *and its stated rationale* and returns a
   verdict: agree, dispute or escalate. Doing nothing is a legitimate outcome —
   an item Anvil has not decided yet has nothing to judge.
4. A **dispute** flags the item back in Anvil. Refresh Anvil and it is there.
5. An **escalation**, or a case nobody settles before its deadline (**lapse**),
   raises a notification through Dispatch. `docker compose logs notifications`
   shows it delivered.

The interesting one is the lapse. Open a few items, leave them, and come back:
nothing else in the system would have told you that an automated decision was
never reviewed.

### What the demo needs from you

**A model provider key**, and nothing else. It goes to LiteLLM only — neither
app ever sees a provider credential, which is exactly the arrangement in a cell,
where the tenant holds a virtual key instead. Both apps fail fast without a
reachable gateway, so a missing key stops the stack at startup with a message
rather than at the first request.

Anvil and loom ask the gateway for **different chat aliases**
(`chat-with-haven-anvil` and `chat-with-haven`). That is the point of naming
models by role: a tenant can decide that the supervisor and the thing it
supervises are not the same model. Here they both resolve to one upstream,
because a demo has one key.

### The edge is faked, and that is a finding

The `edge` service is nginx doing the two things the tenant edge does — strip
the `/{app}` prefix and present an authenticated user via `X-Auth-Request-*`
headers. **It authenticates nobody.**

It exists because of an unresolved platform question, written up in
[SECURITY-edge-header-trust.md](../../SECURITY-edge-header-trust.md): apps trust
those headers, sibling apps can reach each other directly and forge them, and
there is currently no supported way for one app to authenticate to another
in-cluster. loom therefore does **not** set them itself — it calls Anvil through
the edge, as it would in a cell, and in a real cell that call gets a 403 until
the platform side is settled. The shim is a demo prop, not a pattern.

### Tenant provisioning happens too

`dispatch-seed` writes the fixtures a tenant owns — the category, the
subcategory, the recipient, and the one `CREATE_RULE` row that fn-notifications
cannot accept a first rule without. It runs before `notifications` starts,
because the service reads its rules at boot and a row inserted afterwards stays
invisible until the next restart. Those documents are byte-for-byte the ones
that were verified delivering a notification on a live stack.

Everything loom owns — its event type, its routing rule, its template — loom
registers itself over the wire at startup. The split is deliberate and is
described in `DispatchProperties`.

### Shutting down

```bash
docker compose --profile demo down -v      # -v also drops the databases
```

## Run it from an IDE

For working *on* loom rather than watching it: `docker compose up -d` (no
profile) gives you the backing services — Postgres, MongoDB, Temporal,
SeaweedFS and the Dispatch stack — and you run loom yourself. The tenant's
notification fixtures are seeded for you as part of that stack, so
`DISPATCH_ENABLED=true` works from a cold start. One process per terminal, the
standard Spring Boot way:

```bash
backend/mvnw -f backend/pom.xml -DskipTests install   # once, and after cross-module changes
backend/mvnw -f backend/pom.xml -pl api spring-boot:run    # REST 3000 + MCP 3001 — also starts postgres/mongo/temporal/seaweedfs
backend/mvnw -f backend/pom.xml -pl jobs spring-boot:run   # worker + agent daemon
npm --prefix frontend run dev              # UI on http://localhost:5173
```

Or all of it in one terminal: `./dev.sh` runs exactly those commands under
`concurrently`, after a visible `docker compose up -d --wait`.

The `install` matters: `spring-boot:run` resolves the sibling modules
(domain, persistence, …) from the local repository, so cross-module edits
need a fresh `install` before they show up in a running app.

`spring-boot:run` manages the backing services itself
(`spring-boot-docker-compose`): it runs `docker compose up` and waits for
readiness before booting the app, so there is no race even without the compose
step. The stack stays up between runs; stop it with `docker compose down`.
Temporal's UI is on http://localhost:8080.

`spring-boot:run` activates the `local` profile (via `SPRING_PROFILES_ACTIVE` —
override with `-Drun.profiles=…`); everything else (tests, `package`, cells) is
untouched by it. Local values live in `backend/api/src/main/resources/application-local.yaml` and
`backend/jobs/src/main/resources/application-local.yaml` (gitignored — real credentials
go there). The model gateway values must be real (port-forward LiteLLM and use a
tenant's key from its `tenant-models-secret`): the gateway is probed at startup
and the app refuses to boot on failure — the same fail-fast it applies to
Postgres, MongoDB and Temporal, in every environment.

## Build and check

```bash
backend/mvnw -f backend/pom.xml verify              # compiles, runs tests, and enforces the determinism fence
backend/mvnw -f backend/pom.xml -DskipTests verify  # the compile + fence, without the tests
```

The integration tests need Docker (Testcontainers): they run the cases service
against real Postgres — Liquibase migrates, Hibernate validates the entities
against the migrated schema — and prove the MongoDB wiring with a boot-time
ping.

**On Colima**, Testcontainers cannot find the socket by default:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## Before you touch `workflows/`

Temporal replays workflow code, so it must be deterministic. Java has no sandbox to enforce that, so this project builds one: `workflows` cannot see the database or the agent (it will not compile), and ArchUnit bans clock and randomness calls. If the compiler or the enforcer stops you there, it is not in your way — read `CLAUDE.md`.
