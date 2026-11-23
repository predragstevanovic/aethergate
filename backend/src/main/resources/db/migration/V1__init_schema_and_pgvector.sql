-- AetherGate core schema.
-- All objects live in the dedicated "aethergate" schema (Flyway default schema), so the
-- gateway can share a Supabase project with other apps without name collisions and is
-- never exposed through Supabase's auto-generated REST API (which serves "public").
-- Extensions live in "extensions" (Supabase convention); types are schema-qualified.

CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA extensions;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA extensions;

CREATE TABLE tenants (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id         UUID           NOT NULL,
    name                  VARCHAR(120)   NOT NULL,
    slug                  VARCHAR(80)    NOT NULL UNIQUE,
    plan_tier             VARCHAR(32)    NOT NULL DEFAULT 'PRO',
    available_balance_usd NUMERIC(19, 8) NOT NULL DEFAULT 100.00000000,
    reserved_balance_usd  NUMERIC(19, 8) NOT NULL DEFAULT 0.00000000,
    hard_budget_limit_usd NUMERIC(19, 8) NOT NULL DEFAULT 500.00000000,
    is_demo               BOOLEAN        NOT NULL DEFAULT FALSE,
    version               BIGINT         NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    -- Defence in depth: the database itself refuses an overdraft, whatever the app does.
    CONSTRAINT chk_tenants_available_non_negative CHECK (available_balance_usd >= 0),
    CONSTRAINT chk_tenants_reserved_non_negative  CHECK (reserved_balance_usd >= 0),
    CONSTRAINT chk_tenants_budget_positive        CHECK (hard_budget_limit_usd > 0)
);
CREATE UNIQUE INDEX ux_tenants_owner ON tenants (owner_user_id);

CREATE TABLE api_keys (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID         NOT NULL REFERENCES tenants (id),
    name           VARCHAR(100) NOT NULL,
    key_prefix     VARCHAR(16)  NOT NULL,
    key_hash       VARCHAR(64)  NOT NULL UNIQUE,
    rate_limit_rpm INT          NOT NULL DEFAULT 60     CHECK (rate_limit_rpm > 0),
    rate_limit_tpm INT          NOT NULL DEFAULT 100000 CHECK (rate_limit_tpm > 0),
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    last_used_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_api_keys_tenant ON api_keys (tenant_id);

CREATE TABLE provider_configs (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID           NOT NULL REFERENCES tenants (id),
    provider           VARCHAR(32)    NOT NULL CHECK (provider IN ('OPENAI', 'ANTHROPIC', 'GEMINI', 'MOCK_LLM')),
    model_name         VARCHAR(80)    NOT NULL,
    priority_order     INT            NOT NULL CHECK (priority_order > 0),
    input_cost_per_1m  NUMERIC(19, 8) NOT NULL CHECK (input_cost_per_1m >= 0),
    output_cost_per_1m NUMERIC(19, 8) NOT NULL CHECK (output_cost_per_1m >= 0),
    is_enabled         BOOLEAN        NOT NULL DEFAULT TRUE,
    CONSTRAINT ux_provider_priority UNIQUE (tenant_id, priority_order) DEFERRABLE INITIALLY DEFERRED
);

-- Append-only metering ledger. RESERVE = SETTLE + RELEASE for every request_id.
CREATE TABLE ledger_entries (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID           NOT NULL REFERENCES tenants (id),
    request_id        VARCHAR(64)    NOT NULL,
    entry_type        VARCHAR(20)    NOT NULL CHECK (entry_type IN ('TOP_UP', 'RESERVE', 'SETTLE', 'RELEASE')),
    amount_usd        NUMERIC(19, 8) NOT NULL CHECK (amount_usd >= 0),
    balance_after_usd NUMERIC(19, 8) NOT NULL,
    description       VARCHAR(255),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_ledger_idempotency UNIQUE (tenant_id, request_id, entry_type)
);
CREATE INDEX ix_ledger_tenant_created ON ledger_entries (tenant_id, created_at DESC);

CREATE TABLE llm_request_logs (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID           NOT NULL REFERENCES tenants (id),
    request_id         VARCHAR(64)    NOT NULL UNIQUE,
    api_key_id         UUID,
    provider_used      VARCHAR(32)    NOT NULL,
    model_used         VARCHAR(80)    NOT NULL,
    prompt_tokens      INT            NOT NULL,
    completion_tokens  INT            NOT NULL,
    total_cost_usd     NUMERIC(19, 8) NOT NULL,
    saved_cost_usd     NUMERIC(19, 8) NOT NULL DEFAULT 0,
    latency_ms         INT            NOT NULL,
    ttft_ms            INT            NOT NULL,
    cache_status       VARCHAR(16)    NOT NULL CHECK (cache_status IN ('HIT', 'MISS')),
    failover_triggered BOOLEAN        NOT NULL DEFAULT FALSE,
    status_code        INT            NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_logs_tenant_created ON llm_request_logs (tenant_id, created_at DESC);

CREATE TABLE semantic_cache (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID                    NOT NULL REFERENCES tenants (id),
    prompt_text       TEXT                    NOT NULL,
    embedding         extensions.vector(384)  NOT NULL,
    response_text     TEXT                    NOT NULL,
    model_name        VARCHAR(80)             NOT NULL,
    original_cost_usd NUMERIC(19, 8)          NOT NULL DEFAULT 0,
    completion_tokens INT                     NOT NULL DEFAULT 0,
    hit_count         INT                     NOT NULL DEFAULT 0,
    expires_at        TIMESTAMPTZ             NOT NULL,
    created_at        TIMESTAMPTZ             NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_semantic_cache_tenant ON semantic_cache (tenant_id, expires_at);

-- Minute-window counters that let several gateway instances share rate-limit state (ADR-003).
CREATE TABLE rate_limit_windows (
    api_key_id     UUID        NOT NULL REFERENCES api_keys (id),
    tenant_id      UUID        NOT NULL REFERENCES tenants (id),
    window_start   TIMESTAMPTZ NOT NULL,
    request_count  INT         NOT NULL DEFAULT 0,
    token_count    BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (api_key_id, window_start)
);
