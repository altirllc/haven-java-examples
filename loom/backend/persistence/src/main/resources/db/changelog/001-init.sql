--liquibase formatted sql

--changeset loom:001-init
-- Rewritten from the scaffold's `items` table rather than migrated onto it:
-- loom has never been deployed anywhere, so there is no history to preserve and
-- a first changeset describing a table the app does not have would be a lie.
-- From here on the house rule applies — never edit a shipped changeset, add a
-- new file and include it.

-- A case is loom's supervision record for ONE Anvil item. The unique constraint
-- is the invariant, in the database rather than in a check-then-insert race:
-- ingest runs on a timer and will see the same item again.
CREATE TABLE loom.cases (
    id             UUID PRIMARY KEY,
    anvil_item_id  TEXT NOT NULL UNIQUE,
    title          TEXT NOT NULL,
    state          TEXT NOT NULL DEFAULT 'watching',
    -- What Anvil last told us about the item. Nullable: a case can be opened
    -- before Anvil's triager has decided anything, and that gap is exactly what
    -- the SLA measures.
    anvil_status   TEXT,
    anvil_decision JSONB,
    -- loom's own verdict. Nullable until it reviews.
    review         JSONB,
    sla_due_at     TIMESTAMPTZ NOT NULL,
    metadata       JSONB       DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE loom.logs (
    id        UUID PRIMARY KEY,
    case_id   UUID NOT NULL REFERENCES loom.cases (id),
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    action    TEXT NOT NULL,
    actor     TEXT NOT NULL,
    channel   TEXT NOT NULL DEFAULT 'api',
    message   TEXT        DEFAULT '',
    details   JSONB       DEFAULT '{}'
);

CREATE INDEX idx_loom_logs_case ON loom.logs (case_id);

-- The dashboard asks "open and overdue" on every poll and the daemon asks
-- "open" on every sweep. Without this both are a full scan of every case loom
-- has ever opened, which is the one table that only grows.
CREATE INDEX idx_loom_cases_state_sla ON loom.cases (state, sla_due_at);
