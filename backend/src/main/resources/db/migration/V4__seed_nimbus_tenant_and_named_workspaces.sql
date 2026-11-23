-- Third seeded tenant for multi-tenant demos with real email logins.
-- Helix and Nimbus are "named" workspaces (is_demo = FALSE): each is owned by its own Supabase Auth
-- user and is reached through email + password sign-in. Only Vortex stays behind the one-click demo button.
-- owner_user_id is a placeholder until an administrator binds the real Auth user (UNIQUE per tenant).
INSERT INTO tenants (id, owner_user_id, name, slug, plan_tier, available_balance_usd, hard_budget_limit_usd, is_demo)
VALUES ('c4d7a913-5e2b-4f80-a6c1-9b3e7d2f1c03', 'de300000-0000-4000-8000-000000000003',
        'Nimbus Health', 'nimbus-health', 'PRO', 100.00000000, 750.00000000, FALSE);

INSERT INTO ledger_entries (tenant_id, request_id, entry_type, amount_usd, balance_after_usd, description)
VALUES ('c4d7a913-5e2b-4f80-a6c1-9b3e7d2f1c03', 'topup_initial_credit', 'TOP_UP', 100.00000000, 100.00000000, 'Initial credit');

-- Prices in USD per 1M tokens.
INSERT INTO provider_configs (tenant_id, provider, model_name, priority_order, input_cost_per_1m, output_cost_per_1m)
VALUES ('c4d7a913-5e2b-4f80-a6c1-9b3e7d2f1c03', 'OPENAI', 'gpt-4o-mini',      1, 0.15000000, 0.60000000),
       ('c4d7a913-5e2b-4f80-a6c1-9b3e7d2f1c03', 'GEMINI', 'gemini-2.5-flash', 2, 0.30000000, 2.50000000);

UPDATE tenants SET is_demo = FALSE WHERE id = '3b8e5d21-9a4c-4f7e-b2d6-81c0e7f9a402';  -- Helix Robotics
