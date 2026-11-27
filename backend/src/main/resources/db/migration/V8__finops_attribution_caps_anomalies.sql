-- V8: FinOps features.
--   * request attribution: end user, agent session and free-form cost tags on every request (logs + ledger)
--   * spend caps: per API key (monthly), per end user (daily), per agent session (cost + steps)
--   * budget-aware model downgrade (soft limit) and the routing decision recorded per request
--   * cost anomalies (exactly-once per tenant and hour) and cost-control events (blocked requests)
-- Every new table is tenant-owned and isolated by RLS (USING + WITH CHECK), like V7.

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Attribution. The ledger carries the same dimensions as the request log so that caps can be checked
--    under the tenant row lock from the ledger alone (the source of truth for money), never from telemetry.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE llm_request_logs
    ADD COLUMN end_user_id     VARCHAR(128),
    ADD COLUMN session_id      VARCHAR(128),
    ADD COLUMN tags            JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN requested_model VARCHAR(80),
    ADD COLUMN routing_reason  VARCHAR(24);

ALTER TABLE llm_request_logs
    ADD CONSTRAINT chk_logs_tags_object CHECK (jsonb_typeof(tags) = 'object');

CREATE INDEX ix_logs_tenant_user    ON llm_request_logs (tenant_id, end_user_id, created_at DESC) WHERE end_user_id IS NOT NULL;
CREATE INDEX ix_logs_tenant_session ON llm_request_logs (tenant_id, session_id, created_at) WHERE session_id IS NOT NULL;
CREATE INDEX ix_logs_tenant_key     ON llm_request_logs (tenant_id, api_key_id, created_at DESC);
CREATE INDEX ix_logs_tags           ON llm_request_logs USING gin (tags jsonb_path_ops);

ALTER TABLE ledger_entries
    ADD COLUMN api_key_id  UUID,
    ADD COLUMN end_user_id VARCHAR(128),
    ADD COLUMN session_id  VARCHAR(128);

CREATE INDEX ix_ledger_key_month ON ledger_entries (tenant_id, api_key_id, entry_type, created_at) WHERE api_key_id IS NOT NULL;
CREATE INDEX ix_ledger_user_day  ON ledger_entries (tenant_id, end_user_id, entry_type, created_at) WHERE end_user_id IS NOT NULL;
CREATE INDEX ix_ledger_session   ON ledger_entries (tenant_id, session_id, entry_type) WHERE session_id IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Spend caps and the soft-limit downgrade policy.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE api_keys
    ADD COLUMN monthly_budget_usd NUMERIC(19, 8) CHECK (monthly_budget_usd IS NULL OR monthly_budget_usd > 0);

ALTER TABLE tenants
    ADD COLUMN end_user_daily_cap_usd NUMERIC(19, 8) CHECK (end_user_daily_cap_usd IS NULL OR end_user_daily_cap_usd > 0),
    ADD COLUMN session_cap_usd        NUMERIC(19, 8) CHECK (session_cap_usd IS NULL OR session_cap_usd > 0),
    ADD COLUMN session_max_requests   INT            CHECK (session_max_requests IS NULL OR session_max_requests BETWEEN 1 AND 100000),
    ADD COLUMN downgrade_at_pct       INT            CHECK (downgrade_at_pct IS NULL OR downgrade_at_pct BETWEEN 1 AND 100),
    ADD COLUMN downgrade_model        VARCHAR(80),
    ADD CONSTRAINT chk_downgrade_pair CHECK ((downgrade_at_pct IS NULL) = (downgrade_model IS NULL));

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. Cost-control events: one row per (cap kind, scope, period) with a running count of blocked requests.
--    The first insert of a period is what triggers the webhook, decided by the unique constraint.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE cost_control_events (
    id             UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID           NOT NULL REFERENCES tenants (id),
    kind           VARCHAR(32)    NOT NULL,
    scope_key      VARCHAR(160)   NOT NULL,
    period         VARCHAR(10)    NOT NULL,
    limit_value    NUMERIC(19, 8) NOT NULL,
    observed_value NUMERIC(19, 8) NOT NULL,
    blocked_count  INT            NOT NULL DEFAULT 1 CHECK (blocked_count > 0),
    last_request_id VARCHAR(64),
    first_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    last_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_cost_control_kind CHECK (kind IN ('KEY_MONTHLY_BUDGET', 'END_USER_DAILY_CAP', 'SESSION_BUDGET', 'SESSION_STEPS')),
    CONSTRAINT ux_cost_control_scope UNIQUE (tenant_id, kind, scope_key, period)
);
CREATE INDEX ix_cost_control_recent ON cost_control_events (tenant_id, last_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. Cost anomalies: exactly one row per tenant and hourly bucket, so the webhook is sent once.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE cost_anomalies (
    id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID           NOT NULL REFERENCES tenants (id),
    bucket_start    TIMESTAMPTZ    NOT NULL,
    observed_usd    NUMERIC(19, 8) NOT NULL,
    baseline_usd    NUMERIC(19, 8) NOT NULL,
    threshold_usd   NUMERIC(19, 8) NOT NULL,
    z_score         NUMERIC(10, 2) NOT NULL,
    severity        VARCHAR(10)    NOT NULL CHECK (severity IN ('WARNING', 'CRITICAL')),
    top_dimension   VARCHAR(200),
    detected_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ux_cost_anomaly_bucket UNIQUE (tenant_id, bucket_start)
);
CREATE INDEX ix_cost_anomaly_recent ON cost_anomalies (tenant_id, bucket_start DESC);

GRANT SELECT, INSERT, UPDATE ON cost_control_events TO aethergate_app;
GRANT SELECT, INSERT         ON cost_anomalies      TO aethergate_app;

DO
$$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY ['cost_control_events', 'cost_anomalies'] LOOP
        EXECUTE format('ALTER TABLE aethergate.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE aethergate.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON aethergate.%I
                 USING (tenant_id = aethergate.current_tenant_id())
                 WITH CHECK (tenant_id = aethergate.current_tenant_id())', t);
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
            EXECUTE format('REVOKE ALL ON aethergate.%I FROM anon', t);
        END IF;
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
            EXECUTE format('REVOKE ALL ON aethergate.%I FROM authenticated', t);
        END IF;
    END LOOP;
END
$$;
