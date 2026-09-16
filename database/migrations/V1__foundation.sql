-- CodeCycle V1 基础 Schema。业务表按模块文档继续拆分迁移。
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS project;
CREATE SCHEMA IF NOT EXISTS analysis;
CREATE SCHEMA IF NOT EXISTS review;
CREATE SCHEMA IF NOT EXISTS knowledge;

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS analysis.analysis_webhook_events (
    id BIGINT PRIMARY KEY,
    delivery_id VARCHAR(128) NOT NULL UNIQUE,
    event_type VARCHAR(50) NOT NULL,
    repository_id BIGINT,
    commit_sha VARCHAR(64),
    status VARCHAR(20) NOT NULL,
    error TEXT,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
