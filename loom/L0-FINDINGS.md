# loom — L0 spike findings

What the three spikes established, with the evidence. Everything here was run
against a live stack, not read off documentation. Dated 2026-09-15.

The stack is on rc-server (`C:\Users\reconnect\haven-loom`, compose project
`loom`): broker `18001`, events-api `18008`, notifications `18089`, its own
Mongo `27019`, Valkey `16379`.

---

## B — the events client on Boot 4.1: **works**, with a five-line adapter

This was the biggest risk in the whole task and it is now closed.

`co.altir.events.api:events-api-client-spring:2.1.5` is built for Spring Boot
3.5 / Java 17 / Jackson 2. It resolves cleanly under a Boot 4.1 / Spring 7 /
JDK 25 project — Boot 4.1's BOM still manages `com.fasterxml.jackson.core:jackson-databind`,
so Jackson 2 (2.21.4) arrives transitively with a version and coexists with the
Jackson 3 that Boot 4 auto-configures.

It fails at context startup with exactly one problem:

```
Parameter 2 of constructor in co.altir.events.api.client.spring.config.EventsClientConfig
required a bean of type 'com.fasterxml.jackson.databind.ObjectMapper' that could not be found.
```

Boot 4 auto-configures `tools.jackson.databind.ObjectMapper`; the client asks
for the Jackson 2 type. Declaring that bean is the entire fix:

```java
@Bean
ObjectMapper eventsClientObjectMapper() {
    return JsonMapper.builder().addModule(new JavaTimeModule()).build();
}
```

`JavaTimeModule` is not optional — `EventDto` carries `Instant` fields. The two
ObjectMappers are different types in different packages and do not collide:
Spring's own HTTP conversion keeps using the Jackson 3 one.

**Verified:** full round trip on JDK 25 — create input topic, subscribe,
publish, receive — in 32ms.

**Consequence:** the two fallbacks in PLAN.md (talk to the broker over plain
HTTP; pin loom to Boot 3.5 / JDK 21) are both unnecessary. loom stays on the
same Boot 4.1 / JDK 25 stack as `plumb` and the agent scaffold.

---

## A — the notification path: **the hard half is proven**

### What works, proven end to end from outside the service

An external producer registered a notification routing rule in fn-notifications
using nothing but the events pipeline, and got `statusCodeValue: 200` back:

```
publish EventDto{type="my-rule-to-create-rule", context=<rule definition>}
  → tenant-input-topic
  → events-api matches the seeded CREATE_RULE rule, reroutes as REQUEST/CREATE_RULE
  → fn-notifications persists the rule
  → RESPONSE on tenant-output-topic, statusCodeValue=200
```

So loom can register and maintain its own routing rules over the wire. That is
the mechanism the whole notification design rests on.

### The constraint that shapes loom's provisioning

**The first rule cannot be created through any API.** Rules are how the service
turns an external event into an internal one, so creating a rule is itself an
event that needs a rule to route it. The seed `CREATE_RULE` row breaks the cycle
and has to be written **directly into the records store**.

Things that do *not* solve it, checked:

- there is no REST endpoint that creates rules;
- `/api/agent/{tenantId}/rules` is the **AI rules assistant** — a chat endpoint
  needing an Anthropic key and Redis, not a CRUD surface;
- under `RECORDS_STORE=mongo` Liquibase is disabled entirely (ADR-0001), so the
  bundled `tenant-rule.csv` seed is never loaded — and the two stores use
  different physical field names, so a document copied from the CSV shape would
  not match. The Mongo document is camelCase with a composite `_id`, and the
  rule condition field is `condition`, **not** `rule_condition`.

The working seed (verbatim, collection `tenant_rule` in database `notifications`):

```js
{ _id: { tenantId: "default", tenantRuleId: "e68c2b1c-4d06-497f-8d15-02520a6db1bf" },
  externalType: "my-rule-to-create-rule",
  innerType: "REQUEST", innerSubtype: "CREATE_RULE", condition: "true",
  transformations: { id: "context['id']", condition: "context['condition']",
    eventName: "context['eventName']", transformations: "context['transformations']",
    correspondingEventType: "context['correspondingEventType']",
    correspondingEventSubtype: "context['correspondingEventSubtype']",
    requestId: "context['requestId']", topicToRerouteName: "context['topicToRerouteName']" },
  topicToRerouteName: "notification-input-topic",
  createdAt: <now>, updatedAt: <now>, version: NumberLong(0),
  _class: "co.altir.fn.notifications.rebuild.entity.tenant.TenantRule" }
```

The service reads `tenant_rule` at boot and registers each row as an Esper rule,
so **notifications must be restarted after the seed is inserted**.

**This is a tenant-provisioning step, not an application step.** loom must not
write into another service's database; the seed belongs wherever the dispatch
bundle is provisioned. Worth raising separately: the `dispatch` catalog bundle
appears to have no such step, which would make a freshly installed Dispatch
unable to accept its first rule.

### Topics and envelope, resolved

| | |
|---|---|
| Producer publishes to | `tenant-input-topic` |
| Replies and deliveries arrive on | `tenant-output-topic` |
| Rule reroute targets | `notification-input-topic` (common), `immediate-event-input-topic` (priority `IMMEDIATE`) |
| `source` on the event | **do not set it** — in default-tenant mode `EventsConfigProps.normalizeSource` overwrites it at all four consume funnels |

### Delivery: proven end to end

One notification was provisioned and delivered from outside the service, in a
single run. The rendered body came back with the template variables substituted:

```
requestId = APP_PUSH_NOTIFICATION
body = { tenantId: default,
         templateId: 57d7b061-6558-4e77-9293-e6f048402d76,
         categoryId: a85e86bd-…, subcategoryId: 85e076db-…,
         notificationType: APP_PUSH,
         body: "<p>Case CASE-4711 was escalated by loom-agent.</p>",
         target: 50f792c1-80f6-442a-a43f-8ab91cf6aa57,
         title: "Case escalated" }
```

from `templateData: {caseId: "CASE-4711", actor: "loom-agent"}`.

**There is no operator REST API — the CRUD controllers are commented out.**
`CategoryController`, `SubcategoryController`, `TemplateController`,
`UserController`, `UserPreferencesController`, `TenantController` and
`TenantPreferencesController` are each wrapped in `/* … */` in their entirety.
What remains live is auth, the SPA, `/api/tenant` (read), the notification
provider/storage endpoints, the callbacks, the agent chat and the
secret-gated `/external/rest` surface. So **every** fixture is created the same
way a rule is: by publishing an event.

The full provisioning sequence, all over the wire except the first line:

1. seed one `CREATE_RULE` row into the records store (the only step with no wire
   equivalent), restart notifications;
2. publish `my-rule-to-create-rule` five times to register rules for
   `CREATE_CATEGORY`, `CREATE_SUBCATEGORY`, `CREATE_TEMPLATE`, `CREATE_USER`,
   `CREATE_TENANT_PREFS` — each rule's `eventName` is the external event type it
   fires on, and its `transformations` map projects that event's context onto the
   internal request (`{field: "context['field']"}`);
3. publish those five events to create the fixtures;
4. publish one more rule mapping the domain event type →
   `NOTIFICATION` / `SEND_AND_FORGET_NOTIFICATION`, with `notificationType` as a
   literal `APP_PUSH` and `template` as a **quoted Esper literal** (`'<uuid>'`,
   inner quotes included) rather than a context extraction;
5. publish the domain event.

Every step answers on `tenant-output-topic` with `statusCodeValue`, so a
provisioner can verify each one. Re-running is safe: an existing rule answers
`409` rather than breaking the chain.

Channel: **`APP_PUSH`** publishes the rendered notification to a topic instead of
calling a vendor, so the whole path works with no SES / Twilio / Firebase
credentials.

**Correction to an earlier note in this document:** an earlier draft said the
admin REST writes fixtures directly through the services. That was read off
`TemplateController` without noticing that the file is commented out in full.
The pipeline is the only path.

### Two stale things found on the way

- `events-api/docker/compose/docker-compose.yml` pins `:develop` for
  `events-api` and `fn-events-broker`. Those publish workflows emit only
  `v<version>`, `sha-<sha>` and `latest` — **there is no `develop` tag**, so
  that compose file cannot pull. Use `:latest`.
- events-api will not start without `TENANCY_SYNC_KEY` (`@NotBlank`, minimum 10
  characters). It is in neither that compose file nor visibly in the dispatch
  bundle, where it arrives inside the `tenant-events-secret` `envFrom`.

Minor, non-blocking: events-api logs `Unable to find customer 'default'` for
every publish. In default-tenant mode topics are key-less so nothing enforces
it, and the round trip succeeds regardless.

---

## C — reaching Anvil: **blocked, and it surfaced a security problem**

### The security problem

Anvil's REST API trusts the `X-Auth-Request-User` / `-Email` / `-Roles` headers
unconditionally. Its own comment states the assumption:

> *We trust them because the tenant gateway is this app's sole ingress and sets
> them authoritatively — a client cannot spoof them.*

The gateway is **not** the sole ingress. Every app in a tenant reaches every
other app directly at `{app}-api.default:3000` — that is the documented
integration pattern — and such a call never passes the edge. Verified against a
running `ghcr.io/altirllc/haven-anvil-api:latest`:

```
GET /api/items                                        → 403 FORBIDDEN
GET /api/items  -H 'X-Auth-Request-User: loom'
                -H 'X-Auth-Request-Roles: member'     → 200 []
```

So any workload that can reach the Service can impersonate any user in any role,
including `admin`, on every app built this way — the `java/agent` scaffold's
`IdentityResolver` keys on the same headers. **loom will not be built on this**,
and it should be reported and fixed rather than used.

### Why the intended paths are unavailable right now

- **REST with a dev identity.** `identity.ts` mints a local identity only when
  `NODE_ENV !== 'production'` — but the published image cannot run that way:
  `logger.ts` switches to `pino-pretty` on exactly `NODE_ENV=development`, and
  pino-pretty is a devDependency pruned from the runtime image. The container
  crashes at boot with `unable to determine transport target for "pino-pretty"`.
  Confirmed by running it. So **the shipped image is production-identity-only**;
  a dev identity requires running Anvil from source.
- **MCP.** The correct path. It needs a Zitadel JWT whose org equals the tenant
  and whose audience is the tenant's MCP client; with `ZITADEL_ISSUER` and
  `ZITADEL_MCP_AUDIENCE` unset the middleware denies closed, by design. Minting
  a service account for it lives in `haven-operators` / `haven-system`, which we
  have no access to.

### The decision

loom's `AnvilClient` is written against **MCP**, taking a bearer token from
configuration. Locally it points at an Anvil **run from source** (`npm run dev`,
which sets `NODE_ENV=development`) so the dev identity applies; in a cell it
needs a Zitadel service-account token, and until one exists that leg is
unverified. This is the one part of loom that cannot be finished without access
we do not have.

---

## What this changes in PLAN.md

| Risk | Status |
|---|---|
| events client on Boot 4.1 | **closed** — works, one adapter bean |
| notification wiring | **closed** — provisioning and delivery both proven end to end; the only non-wire step is the one seeded `CREATE_RULE` row |
| auth into Anvil | **blocked** — MCP is right, no token available; and the REST header gate is exploitable, so it is not an alternative. See `haven/SECURITY-edge-header-trust.md` |
| model gateway locally | **unchanged** — still needs a provider key |

## What loom now has to own

- A `Notifier` that publishes one domain event and nothing else. loom names the
  event and supplies `templateData`; the tenant's rules decide channel, template
  and audience. That is the same division of labour the Java scaffold used for
  Signal, so the shape of the code carries over.
- A bootstrap that registers loom's own rules idempotently at boot (409 means
  already there) and refuses to write into another service's database.
- Documentation that the tenant needs the `CREATE_RULE` seed before loom can
  provision anything — with the check and the exact document, so it is a
  one-line fix rather than a mystery.


---

## Addendum — what building L6 on top of these findings taught

Two things the spike could not have seen, both found by running loom's shipped
`Dispatch` class against the live stack.

**A rule is acknowledged before it is live.** Creating a rule returns
`statusCodeValue: 200` and the rule is persisted — but an event published
immediately afterwards, matching that rule, gets **no reply at all**. Not an
error; silence. The spike never hit this because it created five rules before
publishing anything, so the first one had long since gone live. A fresh tenant
registering one rule and using it straight away fails. loom now retries the
dependent request (`DispatchAdmin.requestWhenRuleIsLive`).

**A rule's identity is its id, not its event name.** Registering a second rule
with a different id but the same `eventName` does not replace the first — both
stay live and both fire, so the tenant gets two notifications per case. Observed
directly: after changing the configured rule id, the delivered notification came
back carrying the template id from the *previous* rule. Changing
`dispatch.notify-rule-id` therefore needs the old rule decommissioned too.

**Delivery, proven end to end.** loom's own template, rendered with loom's own
variables, delivered to the configured recipient over `APP_PUSH`:

```
notificationType = APP_PUSH
target           = 50f792c1-80f6-442a-a43f-8ab91cf6aa57
body             = <p><b>Vendor NDA Template</b></p>
                   <p>Case b8e53ca9-… (Anvil item anvil-8f1c2e5a) needs a person.</p>
                   <p>Why: lapsed</p><p>loom said: Nobody reviewed this before its deadline.</p>
```

One caveat on the harness that found it: two `Dispatch` instances in one process
share the `loom-admin` consumer group and steal each other's replies. loom runs a
single replica, which is now a documented requirement rather than an accident.

## Addendum — what assembling the demo (L10) taught

**`user_preferences` is not required, and so it is not seeded.** The live stack
delivered a notification with that collection completely empty: an absent user
preference falls back to the tenant's. The demo's seed installs exactly the five
documents that were proven — the `CREATE_RULE` row, the category, the
subcategory, the user and the tenant preferences — and nothing else. Seeding an
unexercised document would be a guess wearing the costume of setup.

The seed was verified rather than assumed: it was run against a scratch database
on the live stack and every document diffed field-by-field (timestamps aside)
against the fixtures that actually delivered. All five match.

**Compose interpolates the entire file regardless of the active profile.** The
demo needs a model provider key and nothing else does, so the obvious
`${OPENAI_API_KEY:?...}` guard looked right — and it broke `docker compose up`
for anyone who only wanted Postgres and Temporal, because compose resolves
variables before it decides which services are in play. The check moved into a
`demo-preflight` service inside the profile, where it can fail loudly for the
demo without holding the everyday stack hostage. Verified both ways on a real
daemon.

**The five `my-rule-to-create-*` rules are an artefact of the L6 harness, not a
requirement.** They exist because the operator CRUD controllers are commented
out, so creating fixtures from *outside* means publishing events, and each kind
of request needs its own routing rule. Seeding the documents directly — which a
tenant provisioning step can do — skips all five. Only the `CREATE_RULE` row is
genuinely unavoidable.

**Three loom-owned ids had defaults that were not valid UUIDs**
(`…-00000000loom`, `…-00000000tmpl`) and the template id defaulted to empty.
They had never been exercised because every live run overrode them. They now
default to the ids that were proven, because the rule and the template belong to
loom: there is nothing for a tenant to decide, and a rule id must stay stable.

**The metered unit had drifted.** The ledger wrote `cases` and `service.json`
declared `cases`, but the `haven_usage` gauge was still tagged `unit="items"`
and the plan allowance still arrived as `PLAN_FREE_ITEMS`. The tag now comes
from `UsageService.UNIT_CASES` so the two cannot disagree again.

## Audit pass, 2026-09-16 — before the stand was torn down

**The edge shim was verified against the real thing, not reasoned about.** An
nginx running the demo's own config was put on the network of the published
`haven-anvil-api:latest` running with `NODE_ENV=production` — the exact shape
the demo uses:

```
GET /api/items  direct, no identity headers   →  403 Forbidden
GET /api/items  through the shim              →  200 OK
```

That settles two things at once: the trust-the-headers finding still holds for
the published image in production mode, and the shim's prefix rewrite
(`/anvil/api/…` → `/api/…`) is correct, which is the path `loom-jobs` takes
through `ANVIL_API_URL`. The nginx config also passes `nginx -t`.

**Anvil's activity feed is capped at 20 entries server-side** (`recentDecisions(20)`
in `api/src/routes/agent.ts`), and that cap is not configurable — `ANVIL_PAGE_SIZE`
only bounds the item list. loom already handles it correctly: absence from the
feed means "not in the recent window", never "undecided", so ingest only ever
adds a decision and never clears one. Worth knowing when driving the demo: create
items a handful at a time, or a case may sit without Anvil's rationale simply
because the decision aged out of the window between polls.

**The plan allowance key was genuinely wrong, not just inconsistent.**
`haven-catalog/schema/catalog-entry.schema.json` states that the platform
projects **`PLAN_FREE_<UNIT uppercased>`** into the service ConfigMap. loom
declares `unit: "cases"` but read `PLAN_FREE_ITEMS`, so in a cell the allowance
would have silently fallen back to the hard-coded default of 10 no matter what
the catalog entry said. Now `PLAN_FREE_CASES`, and `service.json` validates
against that schema.

**Two services defaulted to the same host port.** `temporal-ui` and `events`
both defaulted to 8080, so `docker compose up` failed outright for anyone who
had not copied `.env.example` first. events moved to 8008.
