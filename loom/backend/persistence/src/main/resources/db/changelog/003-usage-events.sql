--liquibase formatted sql

--changeset loom:003-usage-events
CREATE TABLE loom.usage_events (
    id         TEXT PRIMARY KEY,
    case_id    UUID NOT NULL,
    unit       TEXT NOT NULL,
    amount     INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_loom_usage_events_unit_created ON loom.usage_events (unit, created_at);
