# AetherGate — Multi-Tenant AI Gateway & Token Metering Engine

An OpenAI-compatible gateway that sits between B2B SaaS apps and LLM providers and makes AI spend
**deterministic**: every request takes a pre-flight budget hold, streams through a circuit-breaker
protected provider chain, and settles to the exact micro-cent in an append-only ledger. Repeated
questions are answered from a pgvector semantic cache at $0. Tenants are isolated by PostgreSQL
Row-Level Security.

**Console:** [aethergate-console.vercel.app](https://aethergate-console.vercel.app) · **API:** [138-2-169-195.sslip.io](https://138-2-169-195.sslip.io/actuator/health) · one-click *Continue with Demo Workspace*, no signup

| | |
|---|---|
| **Backend** | Java 21 (virtual threads) · Spring Boot 3.5 · Spring Security (JWT + API keys) · JPA · Flyway · Resilience4j · Caffeine |
| **Data** | Supabase PostgreSQL 17 · pgvector (HNSW) · Row-Level Security · Supabase Auth (ES256 JWKS) |
| **Frontend** | React 19 · TypeScript · Vite · Tailwind CSS 4 · Recharts — on Vercel |
| **Ops** | Multi-stage Docker (JRE 21 Alpine, non-root) · Caddy automatic HTTPS · Oracle Cloud Always Free |
| **Docs** | [Deployment explained](docs/DEPLOYMENT_EXPLAINED.md) · [Demo data & live demo](docs/DEMO_DATA_AND_LIVE_DEMO.md) · [Projects](docs/PROJECTS_EXPLAINED.md) · [Tenant CRUD & guardrails](docs/TENANT_CRUD_EXPLAINED.md) · [Team, webhooks, key policies, prompts](docs/TEAM_WEBHOOKS_KEYS_PROMPTS.md) · [System design + ADRs](SYSTEM_DESIGN.md) · [Build blueprint](CLAUDE.md) · [Frontend standard](REACT_FRONTEND_VETERAN_SKILL.md) |

## What it demonstrates

- **Two-phase metering** — `RESERVE` (worst case over the whole fallback chain, under `SELECT … FOR UPDATE`) → stream → `SETTLE` exact cost + `RELEASE` the dust. `RESERVE = SETTLE + RELEASE`, idempotent on `(tenant_id, request_id, entry_type)`. 20 concurrent requests cannot overdraft a balance (tested).
- **Projects (cost centres) with hierarchical budgets** — keys belong to projects; a project's monthly budget is enforced at pre-flight under the same tenant lock (`402 project-budget-exceeded` while the tenant still has money). Full CRUD with `ETag`/`If-Match` (412/428), archive-revokes-keys, delete only without billing history (409), and composite `(tenant_id, project_id)` FKs so even the DB refuses cross-tenant references. → [explained](docs/PROJECTS_EXPLAINED.md)
- **Guardrails** — tenant-defined prompt policies (e-mail, payment cards with network prefix + Luhn, keywords, regex) enforced after rate limiting and *before* the cache, the ledger and any provider: BLOCK → `422` at $0, REDACT → providers, cache and logs only ever see `[REDACTED:rule]`. User regexes run under a 25 ms budget and fail closed (ReDoS-safe). → [explained](docs/TENANT_CRUD_EXPLAINED.md)
- **Team & roles (RBAC)** — invite colleagues as Owner / Admin / Developer / Viewer; roles are enforced by the API on every request (deny-by-default for writes), nobody can grant a role at or above their own, single-use e-mail-bound invitations, workspace switcher, and an append-only audit log that also records refused attempts. → [explained](docs/TEAM_WEBHOOKS_KEYS_PROMPTS.md)
- **Budget alerts + signed webhooks** — "tell me at 80 %" fires exactly once per month (decided by a unique index), delivered through a transactional outbox with `SKIP LOCKED` leases, Stripe-style HMAC signatures, exponential-backoff retries and SSRF protection re-checked on every send.
- **API key policies** — expiry, model allow-list (routing and holds only over allowed models), CIDR IP allow-list, and zero-downtime rotation with a grace period.
- **Prompt library** — versioned `{{variable}}` templates; versions are immutable (DB trigger), Admins publish/roll back, and every request records its version so cost and latency compare per version. Callable from any OpenAI SDK via `prompt_template`.
- **FinOps for AI spend (10 features, V8)** — → [explained](docs/FINOPS_FEATURES_EXPLAINED.md)
  1. **Cost attribution tags** (`X-AetherGate-Tags` / OpenAI `metadata`) with showback by project, model, provider, key, end user or any tag, plus tag-coverage KPI.
  2. **End-user analytics + daily caps** (`X-AetherGate-User` / OpenAI `user`) → `402 end-user-cap-exceeded`.
  3. **Agent session tracing** (`X-AetherGate-Session`): cost, steps, models and a per-step timeline per agent run.
  4. **Runaway-agent guard**: per-session budget (`402`) and step limit (`429 session-step-limit`).
  5. **API key monthly budgets** enforced under the tenant lock (`402 key-budget-exceeded`).
  6. **Budget-aware model downgrade** (soft limit): past N % of the limit the cheaper model answers first (`X-AetherGate-Downgraded-To`).
  7. **Spend forecast**: month-end projection with an 80 % band, limit date, credit runway, per-project outlook.
  8. **Cost anomaly detection**: robust z-score (median + MAD) on hourly spend, exactly-once `cost.anomaly_detected` webhook.
  9. **Savings advisor**: right-sizing, prompt bloat, cache, reliability and governance recommendations with $ estimates from real tokens.
  10. **FOCUS 1.2 export + request explorer**: FinOps-standard CSV whose BilledCost equals the ledger, and a filterable per-request explorer.
- **Tenant self-service CRUD** — model catalog (add / reprice / remove, next hold uses the new price), workspace name + monthly limit (never below committed spend, under the metering row lock), keys moved between projects.
- **Exact money** — `NUMERIC(19,8)` / `BigDecimal` end to end, sent to the browser as decimal strings.
- **Semantic cache** — 384-dim embeddings computed offline in Java, HNSW cosine search, ≥ 0.96 similarity → replay in milliseconds at $0, strictly per tenant.
- **Resilient routing** — Resilience4j retry + circuit breaker per provider; failover only before the first token, so the client's stream is never corrupted. `X-Simulate-Provider-Failure: true` demonstrates it live.
- **Real RLS** — Supabase's `postgres` role bypasses RLS, so each tenant transaction switches to a `NOBYPASSRLS` role. The dashboard shows it: *2,082 foreign rows in DB · 0 visible*.
- **Append-only ledger** — a trigger rejects `UPDATE`/`DELETE`; the app role cannot `TRUNCATE`.
- **Rate limits** — RPM + TPM token buckets (Caffeine) synchronised to PostgreSQL minute windows across instances; `429` + `Retry-After`.
- **Works with zero API keys** — a deterministic streaming simulator stands in for any provider without a key; set `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `GEMINI_API_KEY` to go live.

## Use it from any OpenAI SDK

```bash
curl https://<api-host>/v1/chat/completions \
  -H "Authorization: Bearer ag_live_demo_vortex_5c1e8a2f9b7d4630" \
  -H "Content-Type: application/json" \
  -d '{"model":"auto","stream":true,"messages":[{"role":"user","content":"How does the metering ledger reserve budget?"}]}'
```

Attribute a request to an end user, an agent run and cost tags (all optional):

```bash
curl https://<api-host>/v1/chat/completions -H "Authorization: Bearer ag_live_demo_vortex_5c1e8a2f9b7d4630" \
  -H "X-AetherGate-User: acme-corp" -H "X-AetherGate-Session: agent-run-42" -H "X-AetherGate-Tags: feature=search,env=prod" \
  -H "Content-Type: application/json" -d '{"model":"auto","messages":[{"role":"user","content":"Summarise the ledger design"}]}'
```

Public sandbox keys (demo tenants, only SHA-256 hashes are stored): `ag_live_demo_vortex_5c1e8a2f9b7d4630` (Vortex AI Labs) · `ag_live_demo_helix_0d93b6e1a47c2f58` (Helix Robotics).

## Run locally

```bash
cp .env.example .env.local                      # Supabase + demo values
cd backend && mvn verify                        # 141 tests incl. Testcontainers (Docker required), coverage gate 85 %
java -jar target/aethergate.jar --aethergate.tool=seed   # migrate, seed 30 days of demo traffic, run the 15-check audit
java -jar target/aethergate.jar                  # API on :8080
cd ../frontend && npm install && npm run dev     # console on :5173
```

| Command | Purpose |
|---|---|
| `mvn verify` | Unit + integration + ArchUnit tests, JaCoCo gate (≥ 85 % lines and branches) |
| `mvn org.pitest:pitest-maven:mutationCoverage` | Mutation testing of the pure money / cache / limiter logic |
| `--aethergate.tool=seed` | Reset + seed both demo tenants (≈2,350 requests over 30 days), then audit |
| `--aethergate.tool=audit` | Preview + invariant audit of a live database (exit 1 on any violation) |

## Deploy

- **Frontend → Vercel:** `frontend/` with `VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY`, `VITE_API_BASE_URL`.
- **Backend → Oracle Cloud Always Free:** see [docs/DEPLOYMENT_EXPLAINED.md](docs/DEPLOYMENT_EXPLAINED.md) — `docker compose up -d` builds the image and Caddy provisions HTTPS.
- **Backend → Render (alternative):** [`render.yaml`](render.yaml) blueprint.

## Layout

```
backend/   Spring Boot gateway — config · domain · service (metering, cache, ratelimit, llm, tenant, auth) · web · tools
frontend/  React console — Login · Overview · Playground & Chaos · Ledger · Projects · Guardrails · Prompts · Alerts · Team · Settings (workspace, keys + policies, model catalog)
deploy/    docker-compose.yml · Caddyfile · OCI setup
```
