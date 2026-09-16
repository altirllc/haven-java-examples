# haven-java-examples

Two Java applications on Haven, built to be read as much as run.

| | |
|---|---|
| **[plumb](plumb/)** | The connectivity probe. Round-trips every platform seam a Haven application is wired to and reports each one green or red. Run it first in a new cell or tenant. |
| **[loom](loom/)** | A supervisor built on top of Anvil. Opens a case per item Anvil decides, gives it a deadline, has its own agent judge whether the decision was defensible, flags a disputed one back to Anvil, and raises a notification through Dispatch when a case is escalated or lapses. The notification path is verified end to end; what is not, and why, is in [L0-FINDINGS.md](loom/L0-FINDINGS.md). |

Both live here rather than in their own repositories because their value is
comparative — one shows the platform contract at its thinnest, the other at its
thickest, and reading them side by side is the point.

`loom` also ships the whole demonstration as one command —
`docker compose --profile demo up -d` brings up Anvil, loom, the model gateway
and Dispatch together, so a decision can be watched travelling from one app to a
notification. It needs a model provider key and a **stand-in edge**, and that
shim is itself a finding: see [loom/README.md](loom/README.md#see-the-whole-thing-run).

Plan and the decisions behind it: **[PLAN.md](PLAN.md)**.

## Layout

```
haven-java-examples/
├── plumb/                  one Maven module, one container
│   ├── src/                the probes
│   ├── bundle/             Kustomize; deployment.yaml is the env contract in full
│   ├── docker-compose.yml  the seams, as containers
│   └── CLAUDE.md           read before changing the fail-open behaviour
├── loom/                   eight Maven modules, three containers
│   ├── backend/            domain · contracts · workflows · orchestration
│   │                       persistence · agent · api · jobs
│   ├── frontend/           the React cockpit
│   ├── bundle/             Kustomize + the catalog entry
│   ├── docker-compose.yml  the tenant platform plus the Dispatch stack,
│   │                       and `--profile demo` for the whole story at once
│   ├── L0-FINDINGS.md      what the spikes established, and what is blocked
│   └── CLAUDE.md           the determinism fence, and what not to break
├── .github/workflows/      one path-scoped workflow per app
└── PLAN.md
```

Each app carries its own `README.md` (how to run it) and `CLAUDE.md` (what not to
break). Workflows are path-scoped, so editing one app never rebuilds the other.

## Where the facts came from

Grounded in the live repositories — `haven-scaffold/java/agent` for the Java
application shape, and the `haven-catalog` bundles for what a tenant vCluster
actually injects. `haven-docs` is behind the code on several of these (it still
names `postgres-secret`, `haven-azure-credentials` and `models-vllm`), so where
they disagree, the code wins and plumb proves it by running.
