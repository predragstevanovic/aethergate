# AetherGate — Multi-Tenant AI Gateway & Token Metering Engine

An OpenAI-compatible gateway that sits between B2B SaaS apps and LLM providers and makes AI spend
**deterministic**: every request takes a pre-flight budget hold, streams through a circuit-breaker
protected provider chain, and settles to the exact micro-cent in an append-only ledger. Repeated
questions are answered from a pgvector semantic cache at $0. Tenants are isolated by PostgreSQL
Row-Level Security.

**Console:** _deploying_ · **API:** [138-2-169-195.sslip.io](https://138-2-169-195.sslip.io/actuator/health) · one-click *Continue with Demo Workspace*, no signup

| | |
|---|---|
| **Backend** | Java 21 (virtual threads) · Spring Boot 3.5 · Spring Security (JWT + API keys) · JPA · Flyway · Resilience4j · Caffeine |
| **Data** | Supabase PostgreSQL 17 · pgvector (HNSW) · Row-Level Security · Supabase Auth (ES256 JWKS) |
| **Frontend** | React 19 · TypeScript · Vite · Tailwind CSS 4 · Recharts — on Vercel |
| **Ops** | Multi-stage Docker (JRE 21 Alpine, non-root) · Caddy automatic HTTPS · Oracle Cloud Always Free |
| **Docs** | [System design + ADRs](SYSTEM_DESIGN.md) · [Build blueprint](CLAUDE.md) · [Frontend standard](REACT_FRONTEND_VETERAN_SKILL.md) |

## What it demonstrates

- **Two-phase metering** — `RESERVE` (worst case over the whole fallback chain, under `SELECT … FOR UPDATE`) → stream → `SETTLE` exact cost + `RELEASE` the dust. `RESERVE = SETTLE + RELEASE`, idempotent on `(tenant_id, request_id, entry_type)`. 20 concurrent requests cannot overdraft a balance (tested).
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

Public sandbox keys (demo tenants, only SHA-256 hashes are stored): `ag_live_demo_vortex_5c1e8a2f9b7d4630` (Vortex AI Labs) · `ag_live_demo_helix_0d93b6e1a47c2f58` (Helix Robotics).

## Run locally

```bash
cp .env.example .env.local                      # Supabase + demo values
cd backend && ./mvnw verify                      # 98 tests incl. Testcontainers (Docker required), coverage gate 85 %
java -jar target/aethergate.jar --aethergate.tool=seed   # migrate, seed 30 days of demo traffic, run the 10-check audit
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
frontend/  React console — Login · Overview · Playground & Chaos · Ledger · Keys & Routing
deploy/    docker-compose.yml · Caddyfile · OCI setup
```
