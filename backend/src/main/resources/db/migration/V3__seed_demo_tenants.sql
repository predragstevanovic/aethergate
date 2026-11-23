-- Two public demo tenants. Their API keys are deliberately public sandbox keys
-- (documented in README); only their SHA-256 hashes are stored.
-- owner_user_id is a placeholder: the demo-session endpoint binds Vortex to the real
-- Supabase Auth demo user on first login. Rich history is added by the seeder tool.

INSERT INTO tenants (id, owner_user_id, name, slug, plan_tier, available_balance_usd, hard_budget_limit_usd, is_demo)
VALUES ('7f1c2a9e-4b3d-4e8a-9c61-2d5e8f0a1b01', 'de300000-0000-4000-8000-000000000001',
        'Vortex AI Labs', 'vortex-ai-labs', 'PRO', 100.00000000, 500.00000000, TRUE),
       ('3b8e5d21-9a4c-4f7e-b2d6-81c0e7f9a402', 'de300000-0000-4000-8000-000000000002',
        'Helix Robotics', 'helix-robotics', 'SCALE', 100.00000000, 2000.00000000, TRUE);

INSERT INTO ledger_entries (tenant_id, request_id, entry_type, amount_usd, balance_after_usd, description)
VALUES ('7f1c2a9e-4b3d-4e8a-9c61-2d5e8f0a1b01', 'topup_initial_credit', 'TOP_UP', 100.00000000, 100.00000000, 'Initial credit'),
       ('3b8e5d21-9a4c-4f7e-b2d6-81c0e7f9a402', 'topup_initial_credit', 'TOP_UP', 100.00000000, 100.00000000, 'Initial credit');

-- Prices in USD per 1M tokens.
INSERT INTO provider_configs (tenant_id, provider, model_name, priority_order, input_cost_per_1m, output_cost_per_1m)
VALUES ('7f1c2a9e-4b3d-4e8a-9c61-2d5e8f0a1b01', 'OPENAI',    'gpt-4o',            1, 2.50000000, 10.00000000),
       ('7f1c2a9e-4b3d-4e8a-9c61-2d5e8f0a1b01', 'ANTHROPIC', 'claude-sonnet-4-5', 2, 3.00000000, 15.00000000),
       ('3b8e5d21-9a4c-4f7e-b2d6-81c0e7f9a402', 'ANTHROPIC', 'claude-haiku-4-5', 1, 1.00000000, 5.00000000),
       ('3b8e5d21-9a4c-4f7e-b2d6-81c0e7f9a402', 'GEMINI',    'gemini-2.5-flash', 2, 0.30000000, 2.50000000);

INSERT INTO api_keys (tenant_id, name, key_prefix, key_hash, rate_limit_rpm, rate_limit_tpm)
VALUES ('7f1c2a9e-4b3d-4e8a-9c61-2d5e8f0a1b01', 'Public demo key', 'ag_live_demo',
        encode(sha256(convert_to('ag_live_demo_vortex_5c1e8a2f9b7d4630', 'UTF8')), 'hex'), 60, 100000),
       ('3b8e5d21-9a4c-4f7e-b2d6-81c0e7f9a402', 'Public demo key', 'ag_live_demo',
        encode(sha256(convert_to('ag_live_demo_helix_0d93b6e1a47c2f58', 'UTF8')), 'hex'), 120, 200000);
