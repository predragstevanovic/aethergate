-- Guardrails: tenant-defined prompt policies applied by the gateway BEFORE the cache and any provider sees
-- the prompt. BLOCK rejects the request (no hold, no cost); REDACT masks the match and lets it through.
CREATE TABLE guardrail_rules (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID          NOT NULL REFERENCES tenants (id),
    name        VARCHAR(80)   NOT NULL,
    rule_type   VARCHAR(20)   NOT NULL,
    pattern     VARCHAR(500),
    action      VARCHAR(10)   NOT NULL,
    is_enabled  BOOLEAN       NOT NULL DEFAULT TRUE,
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_guardrail_type    CHECK (rule_type IN ('KEYWORDS', 'REGEX', 'PII_EMAIL', 'PII_CARD')),
    CONSTRAINT chk_guardrail_action  CHECK (action IN ('BLOCK', 'REDACT')),
    -- presets carry no pattern; keyword lists and regexes must have one
    CONSTRAINT chk_guardrail_pattern CHECK ((rule_type IN ('KEYWORDS', 'REGEX')) = (pattern IS NOT NULL)),
    CONSTRAINT ux_guardrail_tenant_id UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX ux_guardrail_tenant_name ON guardrail_rules (tenant_id, lower(name));

-- One row per rule that fired on a request (the request itself may never reach the ledger when blocked).
-- rule_name is a snapshot so the audit trail survives deleting or renaming the rule.
CREATE TABLE guardrail_events (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID          NOT NULL REFERENCES tenants (id),
    rule_id     UUID,
    rule_name   VARCHAR(80)   NOT NULL,
    action      VARCHAR(10)   NOT NULL,
    request_id  VARCHAR(64)   NOT NULL,
    matches     INT           NOT NULL CHECK (matches > 0),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_guardrail_events_rule FOREIGN KEY (tenant_id, rule_id)
        REFERENCES guardrail_rules (tenant_id, id) ON DELETE SET NULL (rule_id)
);
CREATE INDEX ix_guardrail_events_tenant ON guardrail_events (tenant_id, created_at DESC);
CREATE INDEX ix_guardrail_events_rule   ON guardrail_events (tenant_id, rule_id, created_at);

-- Model catalog CRUD: one row per (provider, model) per tenant.
CREATE UNIQUE INDEX ux_provider_model ON provider_configs (tenant_id, provider, lower(model_name));

GRANT SELECT, INSERT, UPDATE, DELETE ON guardrail_rules  TO aethergate_app;
GRANT SELECT, INSERT                 ON guardrail_events TO aethergate_app;
GRANT DELETE                         ON provider_configs TO aethergate_app;

DO
$$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY ['guardrail_rules', 'guardrail_events'] LOOP
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
