# plumb

The Haven connectivity probe: one Spring Boot container that round-trips every
platform seam and reports each green or red. Boot 4.1, JDK 25 — the same stack as
`scaffold/java/agent`, because a probe running on a different stack than the apps
it vouches for would prove nothing about them.

## Critical

CRITICAL - **plumb is fail-OPEN. Every other Haven service is fail-fast.** A
missing seam must never stop this process from booting, or it cannot report the
missing seam. This is the whole product; treat any change that weakens it as a
bug, not a cleanup.
CRITICAL - Records — every table lives in the `plumb` schema. Never write to
`public` or another app's schema.
CRITICAL - Workflows — every `workflowId` is prefixed `plumb-`; the task queue is
`plumb`. A worker on someone else's queue steals their work.
CRITICAL - Storage — every S3 key is prefixed `plumb/` inside the tenant bucket.
Never write to the bucket root.
CRITICAL - Readiness carries NO seam checks. A pod pulled out of service when
Postgres blips is a probe that goes dark exactly when it is needed.

## What fail-open costs, and why each cost is deliberate

These four look like omissions. They are the design, and each one has a specific
failure it prevents:

1. **No `spring-boot-starter-data-jpa`, no `-data-mongodb`, no
   `-jdbc`.** Drivers only. Those starters autoconfigure a connection at boot and
   would kill the process before the first page render. A pleasant side effect:
   Boot contributes no `db` or `mongo` health indicator, so there is nothing to
   remember to exclude from the readiness group.
2. **No `@Validated` / `@NotBlank` on any seam property.** `haven.plumb.config`
   records are all nullable. `Values.isSet` and each record's `configured()` turn
   an absent value into a SKIPPED row naming the variable.
3. **Every client is built lazily, inside the probe that owns it.** Temporal is
   the one exception worth knowing about: `TemporalSeam` caches its connection
   and worker because a worker is a poller, and building one per sweep would
   leave a trail of pollers on the task queue. A failed connect discards the
   cache.
4. **`ProbeRegistry` is the only place that catches.** Probes throw; the registry
   converts a throw into FAIL and a hang into FAIL-after-timeout. That is why the
   probes read like the round trip they perform, with no defensive plumbing.
   `ProbeRegistryTest` pins all of it.

## Structure

```
src/main/java/haven/plumb/
  config/      @ConfigurationProperties records, one per seam. Nullable by design.
  probe/       Probe, Outcome, ProbeResult, ProbeRegistry (fail-open lives here), ProbeSchedule
  probe/impl/  one class per seam + Http (the shared JDK HttpClient)
  temporal/    PingWorkflow/Activities + TemporalSeam (the cached connection and worker)
  web/         ProbeController (JSON), Page (the HTML), PageController
  metrics/     ProbeMetrics — the Prometheus gauges
bundle/        Kustomize manifests. deployment.yaml is the env contract, in full.
```

## Conventions

- **Probes write and read back.** Opening a connection proves the network; it
  does not prove credentials, grants, schema, or that the service on the other
  end is the one you think it is. Where a seam allows a write, write — then
  delete it. Where it does not (identity, siblings), say what you actually
  checked in `proves()`.
- **Evidence is what makes a row believable.** `Outcome.ok(detail, evidence...)`
  — put the server version, the object key, the row id, the hostname that
  executed the activity in there. A green row with nothing under it is a claim; a
  green row listing what it did is a result.
- **Never invent evidence.** The Temporal row reports the host that ran the
  activity because the activity actually returns it. If you cannot observe
  something, do not print it — a plausible fabrication in a diagnostic is worse
  than a blank.
- **A SKIPPED detail must name the variable** that would enable the check. That
  string is the answer to "why is this grey", and it is the single most-read text
  in the app.
- **`Failures.describe` prints the whole cause chain.** Seam failures are almost
  always reported by a wrapper whose own message is useless while the cause two
  levels down is the answer.
- **The page escapes everything.** Details come from configuration and from
  exception messages, and an exception message is arbitrary text from a remote
  system. `PageTest` pins it.
- **Metrics: SKIPPED is NaN, not 0.** 0 means broken and should page someone;
  a seam the tenant does not use has no value at all. Encoding it as 0 makes
  every correctly provisioned tenant look like an outage.
- **Config**: `@ConfigurationProperties` records, read through the record. Never
  `System.getenv`. `application.yaml` defaults everything to empty and names the
  bundle's variable, so the file doubles as the contract.

## The Temporal workflow is deliberately trivial

A real Haven Java app walls workflow code off from Spring, Hibernate and IO with
a module graph, a Maven enforcer rule and ArchUnit — Temporal replays workflow
code and Java has no sandbox to make that safe. plumb is one module and has no
such fence, which is acceptable **only** because `PingWorkflowImpl` does nothing
but call one activity. If a second line ever needs to go in there, it belongs in
an activity. If plumb ever needs real workflows, it needs the scaffold's module
split first.

## Commands

```bash
./mvnw verify                                  # compile + the fail-open contract tests
./mvnw spring-boot:run -Drun.profiles=rc       # against the containers on rc-server
./mvnw spring-boot:run                         # local profile, everything on this machine
kubectl kustomize bundle                       # render the deployment
```

Tests need no Docker. The seams live in `docker-compose.yml`; on this workstation
they run on rc-server — see README for the pull caveat over SSH.

## Docker

```bash
docker build -t plumb .
```

Builds from `plumb/` on the `ghcr.io/altirllc/haven-base-jvm*:25` images, which
are private — `docker login ghcr.io` first. CI (`.github/workflows/plumb.yml`)
builds and pushes `ghcr.io/altirllc/plumb` on pushes touching `plumb/**`.
