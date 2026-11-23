-- Projects (cost centres): a tenant splits its spend across products/teams, each with an optional
-- monthly budget enforced by the same pre-flight hold as the tenant budget (hierarchical budgets).
CREATE TABLE projects (
    id                 UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID           NOT NULL REFERENCES tenants (id),
    name               VARCHAR(80)    NOT NULL,
    description        VARCHAR(280),
    monthly_budget_usd NUMERIC(19, 8),
    status             VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',
    version            BIGINT         NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    archived_at        TIMESTAMPTZ,
    CONSTRAINT chk_projects_status        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT chk_projects_budget        CHECK (monthly_budget_usd IS NULL OR monthly_budget_usd > 0),
    CONSTRAINT chk_projects_archived_at   CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL)),
    CONSTRAINT chk_projects_name_not_blank CHECK (length(btrim(name)) >= 2),
    -- target of the composite foreign keys below: a row can only point at a project of ITS OWN tenant
    CONSTRAINT ux_projects_tenant_id UNIQUE (tenant_id, id)
);
-- Names are unique per tenant among ACTIVE projects (case-insensitive); archived names can be reused.
CREATE UNIQUE INDEX ux_projects_tenant_active_name ON projects (tenant_id, lower(name)) WHERE status = 'ACTIVE';
CREATE INDEX ix_projects_tenant ON projects (tenant_id, status);

-- Foreign keys are composite (tenant_id, project_id): PostgreSQL checks FKs without RLS, so a plain
-- project_id FK would let one tenant's row reference another tenant's project. This makes it impossible.
-- A key belongs to at most one project; deleting a never-used project leaves its keys unassigned.
ALTER TABLE api_keys ADD COLUMN project_id UUID;
ALTER TABLE api_keys ADD CONSTRAINT fk_api_keys_project FOREIGN KEY (tenant_id, project_id)
    REFERENCES projects (tenant_id, id) ON DELETE SET NULL (project_id);
CREATE INDEX ix_api_keys_project ON api_keys (project_id);

-- Every metering line and request log carries the project it was charged to. RESTRICT: a project
-- with billing history can never be hard-deleted (the ledger is append-only), only archived.
ALTER TABLE ledger_entries   ADD COLUMN project_id UUID;
ALTER TABLE llm_request_logs ADD COLUMN project_id UUID;
ALTER TABLE ledger_entries   ADD CONSTRAINT fk_ledger_project FOREIGN KEY (tenant_id, project_id)
    REFERENCES projects (tenant_id, id) ON DELETE RESTRICT;
ALTER TABLE llm_request_logs ADD CONSTRAINT fk_logs_project FOREIGN KEY (tenant_id, project_id)
    REFERENCES projects (tenant_id, id) ON DELETE RESTRICT;
CREATE INDEX ix_ledger_project ON ledger_entries (tenant_id, project_id, entry_type, created_at);
CREATE INDEX ix_logs_project   ON llm_request_logs (tenant_id, project_id, created_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON projects TO aethergate_app;

ALTER TABLE projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE projects FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON projects
    USING (tenant_id = aethergate.current_tenant_id())
    WITH CHECK (tenant_id = aethergate.current_tenant_id());

DO
$$
DECLARE
    r text;
BEGIN
    FOREACH r IN ARRAY ARRAY ['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('REVOKE ALL ON aethergate.projects FROM %I', r);
        END IF;
    END LOOP;
END
$$;
