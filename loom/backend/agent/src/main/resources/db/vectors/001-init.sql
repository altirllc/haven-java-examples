--liquibase formatted sql

--changeset loom:vectors-001-init
-- Verbatim what PgVectorStore.initializeSchema(true) would run (2.0.0
-- defaults: public schema, uuid ids, HNSW cosine). 1024 = text-embedding-1024,
-- the one embedding model platform-wide;
-- vectorTableValidationsEnabled makes dimension drift fail at boot.
-- Everything IF NOT EXISTS: the platform pre-installs the vector extension,
-- so this must apply cleanly to a non-empty database.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS public.vector_store (
    id uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content text,
    metadata json,
    embedding vector(1024)
);

CREATE INDEX IF NOT EXISTS spring_ai_vector_index ON public.vector_store USING hnsw (embedding vector_cosine_ops);
