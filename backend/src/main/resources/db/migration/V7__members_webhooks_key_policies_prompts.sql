-- V7: team members & roles, audit log, budget alerts + signed webhooks (outbox), API key policies,
-- versioned prompt templates. Every new table is tenant-owned and isolated by RLS (USING + WITH CHECK).

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Team members (RBAC). The tenant's owner_user_id stays the primary OWNER and is never stored here;
--    a row is either a pending invitation (no user yet) or an active member bound to a Supabase user.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE tenant_members (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID          NOT NULL REFERENCES tenants (id),
    user_id           UUID,
    email             VARCHAR(254)  NOT NULL,
    role              VARCHAR(12)   NOT NULL,
    status            VARCHAR(10)   NOT NULL,
    invite_token_hash VARCHAR(64)   UNIQUE,
    invite_expires_at TIMESTAMPTZ,
    invited_by_email  VARCHAR(254),
    joined_at         TIMESTAMPTZ,
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_member_role   CHECK (role IN ('OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER')),
    CONSTRAINT chk_member_status CHECK (status IN ('INVITED', 'ACTIVE')),
    -- an invitation has a token and no user; an active member has a user and no token
    CONSTRAINT chk_member_shape  CHECK ((status = 'ACTIVE' AND user_id IS NOT NULL AND invite_token_hash IS NULL)
                                     OR (status = 'INVITED' AND user_id IS NULL AND invite_token_hash IS NOT NULL)),
    CONSTRAINT ux_member_tenant_id UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX ux_member_tenant_email ON tenant_members (tenant_id, lower(email));
CREATE UNIQUE INDEX ux_member_tenant_user  ON tenant_members (tenant_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX ix_member_user ON tenant_members (user_id) WHERE status = 'ACTIVE';

-- Who changed what. Append-only, exactly like the ledger.
CREATE TABLE audit_events (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID          NOT NULL REFERENCES tenants (id),
    actor_id     UUID,
    actor_email  VARCHAR(254),
    actor_role   VARCHAR(12),
    action       VARCHAR(160)  NOT NULL,
    target_id    VARCHAR(64),
    status_code  INT           NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_tenant_created ON audit_events (tenant_id, created_at DESC);

CREATE OR REPLACE FUNCTION aethergate.reject_audit_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'SECURITY VIOLATION: audit events are strictly append-only'
        USING ERRCODE = 'insufficient_privilege';
END;
$$;
CREATE TRIGGER trg_audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION aethergate.reject_audit_mutation();

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Budget alerts and webhooks. Deliveries are a transactional outbox: they are written in the same
--    transaction as the fact they announce and sent by a background dispatcher after commit.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE webhook_endpoints (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID          NOT NULL REFERENCES tenants (id),
    name         VARCHAR(80)   NOT NULL,
    url          VARCHAR(500)  NOT NULL,
    secret       VARCHAR(80)   NOT NULL,
    event_types  VARCHAR(300)  NOT NULL,
    is_enabled   BOOLEAN       NOT NULL DEFAULT TRUE,
    version      BIGINT        NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_webhook_tenant_id UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX ux_webhook_tenant_name ON webhook_endpoints (tenant_id, lower(name));

CREATE TABLE webhook_deliveries (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID          NOT NULL REFERENCES tenants (id),
    endpoint_id      UUID          NOT NULL,
    event_id         VARCHAR(40)   NOT NULL,
    event_type       VARCHAR(40)   NOT NULL,
    payload          TEXT          NOT NULL,
    status           VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    attempts         INT           NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_status_code INT,
    last_error       VARCHAR(300),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    delivered_at     TIMESTAMPTZ,
    CONSTRAINT chk_delivery_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'DEAD')),
    CONSTRAINT fk_delivery_endpoint FOREIGN KEY (tenant_id, endpoint_id)
        REFERENCES webhook_endpoints (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_delivery_due      ON webhook_deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_delivery_endpoint ON webhook_deliveries (tenant_id, endpoint_id, created_at DESC);

CREATE TABLE alert_rules (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID          NOT NULL REFERENCES tenants (id),
    project_id     UUID,
    threshold_pct  INT           NOT NULL CHECK (threshold_pct BETWEEN 1 AND 100),
    is_enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_alert_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_alert_project FOREIGN KEY (tenant_id, project_id)
        REFERENCES projects (tenant_id, id) ON DELETE CASCADE
);
-- one rule per scope and threshold (NULL project = the whole workspace)
CREATE UNIQUE INDEX ux_alert_scope ON alert_rules (tenant_id, coalesce(project_id, '00000000-0000-0000-0000-000000000000'::uuid), threshold_pct);

-- Exactly-once per rule and calendar month, guaranteed by the database, not by the application.
CREATE TABLE alert_firings (
    id          UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID           NOT NULL REFERENCES tenants (id),
    rule_id     UUID           NOT NULL,
    period      CHAR(7)        NOT NULL,
    spend_usd   NUMERIC(19, 8) NOT NULL,
    limit_usd   NUMERIC(19, 8) NOT NULL,
    fired_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ux_alert_firing_period UNIQUE (rule_id, period),
    CONSTRAINT fk_firing_rule FOREIGN KEY (tenant_id, rule_id)
        REFERENCES alert_rules (tenant_id, id) ON DELETE CASCADE
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. API key policies: expiry, model allow-list, IP allow-list, rotation lineage, optimistic version.
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE api_keys
    ADD COLUMN expires_at     TIMESTAMPTZ,
    ADD COLUMN allowed_models VARCHAR(500),
    ADD COLUMN ip_allowlist   VARCHAR(500),
    ADD COLUMN rotated_from   UUID,
    ADD COLUMN version        BIGINT NOT NULL DEFAULT 0;

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. Prompt templates with immutable versions; the request log records which version was used.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE prompt_templates (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID          NOT NULL REFERENCES tenants (id),
    name            VARCHAR(80)   NOT NULL,
    description     VARCHAR(280),
    active_version  INT           NOT NULL DEFAULT 1,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_prompt_tenant_id UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX ux_prompt_tenant_name ON prompt_templates (tenant_id, lower(name));

CREATE TABLE prompt_template_versions (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID          NOT NULL REFERENCES tenants (id),
    template_id      UUID          NOT NULL,
    version_no       INT           NOT NULL CHECK (version_no > 0),
    system_prompt    TEXT,
    user_template    TEXT          NOT NULL,
    variables        VARCHAR(500)  NOT NULL DEFAULT '',
    note             VARCHAR(200),
    created_by_email VARCHAR(254),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_prompt_version UNIQUE (template_id, version_no),
    CONSTRAINT fk_prompt_version_template FOREIGN KEY (tenant_id, template_id)
        REFERENCES prompt_templates (tenant_id, id) ON DELETE CASCADE
);

CREATE OR REPLACE FUNCTION aethergate.reject_prompt_version_update() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Prompt template versions are immutable; create a new version instead'
        USING ERRCODE = 'insufficient_privilege';
END;
$$;
CREATE TRIGGER trg_prompt_versions_immutable
    BEFORE UPDATE ON prompt_template_versions
    FOR EACH ROW EXECUTE FUNCTION aethergate.reject_prompt_version_update();

ALTER TABLE llm_request_logs
    ADD COLUMN template_id      UUID,
    ADD COLUMN template_version INT,
    ADD CONSTRAINT fk_logs_template FOREIGN KEY (tenant_id, template_id)
        REFERENCES prompt_templates (tenant_id, id);
CREATE INDEX ix_logs_template ON llm_request_logs (tenant_id, template_id, template_version) WHERE template_id IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- Grants + RLS for every new table.
-- ─────────────────────────────────────────────────────────────────────────────
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_members           TO aethergate_app;
GRANT SELECT, INSERT                 ON audit_events             TO aethergate_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON webhook_endpoints        TO aethergate_app;
GRANT SELECT, INSERT, UPDATE         ON webhook_deliveries       TO aethergate_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON alert_rules              TO aethergate_app;
GRANT SELECT, INSERT                 ON alert_firings            TO aethergate_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON prompt_templates         TO aethergate_app;
GRANT SELECT, INSERT, DELETE         ON prompt_template_versions TO aethergate_app;

DO
$$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY ['tenant_members', 'audit_events', 'webhook_endpoints', 'webhook_deliveries',
                              'alert_rules', 'alert_firings', 'prompt_templates', 'prompt_template_versions'] LOOP
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
