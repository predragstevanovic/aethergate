-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Append-only ledger: history can only grow.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION aethergate.reject_ledger_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'SECURITY VIOLATION: Ledger entries are strictly append-only'
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION aethergate.reject_ledger_mutation();

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Tenant context + a role that really is subject to RLS.
--    Supabase's "postgres" role has BYPASSRLS (and test containers run as superuser),
--    so every tenant-scoped transaction switches to aethergate_app (SET LOCAL ROLE).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION aethergate.current_tenant_id() RETURNS uuid
    LANGUAGE sql STABLE AS
$$ SELECT NULLIF(current_setting('app.current_tenant_id', true), '')::uuid $$;

DO
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aethergate_app') THEN
        CREATE ROLE aethergate_app NOLOGIN NOBYPASSRLS;
    END IF;
END
$$;
GRANT aethergate_app TO CURRENT_USER;

GRANT USAGE ON SCHEMA aethergate TO aethergate_app;
GRANT USAGE ON SCHEMA extensions TO aethergate_app;
GRANT EXECUTE ON FUNCTION aethergate.current_tenant_id() TO aethergate_app;

GRANT SELECT, UPDATE                 ON tenants            TO aethergate_app;
GRANT SELECT, INSERT, UPDATE         ON api_keys           TO aethergate_app;
GRANT SELECT, INSERT, UPDATE         ON provider_configs   TO aethergate_app;
GRANT SELECT, INSERT                 ON ledger_entries     TO aethergate_app;
GRANT SELECT, INSERT                 ON llm_request_logs   TO aethergate_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON semantic_cache     TO aethergate_app;
GRANT SELECT, INSERT, UPDATE         ON rate_limit_windows TO aethergate_app;

-- Supabase API roles never touch this schema (it is also not exposed via PostgREST).
DO
$$
DECLARE
    r text;
BEGIN
    FOREACH r IN ARRAY ARRAY ['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA aethergate FROM %I', r);
            EXECUTE format('REVOKE ALL ON SCHEMA aethergate FROM %I', r);
        END IF;
    END LOOP;
END
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. Row-Level Security on every tenant-owned table (USING + WITH CHECK).
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenants
    USING (id = aethergate.current_tenant_id())
    WITH CHECK (id = aethergate.current_tenant_id());

DO
$$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY ['api_keys', 'provider_configs', 'ledger_entries',
                              'llm_request_logs', 'semantic_cache', 'rate_limit_windows'] LOOP
        EXECUTE format('ALTER TABLE aethergate.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE aethergate.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON aethergate.%I
                 USING (tenant_id = aethergate.current_tenant_id())
                 WITH CHECK (tenant_id = aethergate.current_tenant_id())', t);
    END LOOP;
END
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. Approximate nearest-neighbour index for the semantic cache.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX idx_semantic_cache_embedding
    ON semantic_cache USING hnsw (embedding extensions.vector_cosine_ops);
