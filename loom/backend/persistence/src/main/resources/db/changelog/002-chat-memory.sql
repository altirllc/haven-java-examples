--liquibase formatted sql

--changeset loom:002-chat-memory
-- Verbatim copy of Spring AI 2.0.0's schema-postgresql.sql (its unlocked boot
-- initializer is off — concurrent api/jobs boots raced it). Unqualified like
-- the library script; Liquibase's default-schema pins it to `loom`.

CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp" TIMESTAMP NOT NULL,
    sequence_id BIGINT NOT NULL
    );

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
ON SPRING_AI_CHAT_MEMORY(conversation_id, "timestamp");

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX
ON SPRING_AI_CHAT_MEMORY(conversation_id, sequence_id);
