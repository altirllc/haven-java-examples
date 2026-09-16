--liquibase formatted sql

--changeset plumb:001-heartbeat
-- No schema qualifier: Liquibase runs with defaultSchemaName set to the
-- configured schema, so this lands wherever POSTGRES_SCHEMA points and the
-- table name stays correct if a tenant needs a different one.
CREATE TABLE heartbeat (
    id         UUID PRIMARY KEY,
    note       TEXT        NOT NULL DEFAULT '',
    written_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
