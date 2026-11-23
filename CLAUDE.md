# 🚀 CLAUDE.md — AetherGate: Multi-Tenant AI Gateway & Token Metering Engine
### Autonomous Full-Stack Execution Blueprint (Java 21 + Spring Boot + Supabase + React + Vercel)

> **DIRECTIVE FOR CLAUDE CODE:**
> You are acting as a Principal Full-Stack & Distributed Systems Engineer. Read this `CLAUDE.md` file alongside `REACT_FRONTEND_VETERAN_SKILL.md` before writing any code. Your mission is to build, verify, and prepare for deployment **AetherGate**, a production-grade Multi-Tenant AI Gateway, Token Metering & Semantic Caching platform.
>
> **CRITICAL SECURITY RULE:** Never hardcode credentials, tokens, or secrets into source files, `.md` documents, or Git commits. Always read secrets from `.env.local` / environment variables and verify `.gitignore` excludes all `.env*` files.

---

## 1. Tech Stack & Free-Tier Cloud Topology

### 1.1. Architecture Overview
- **Frontend (`frontend/`):** React 19 + TypeScript + Vite + Tailwind CSS + `lucide-react` + `recharts`. Hosted on **Vercel** (Free Tier). Uses **Supabase Auth** (`@supabase/supabase-js`) for a sleek, minimalist Login/Signup experience and attaches the Supabase JWT (`Authorization: Bearer <access_token>`) to all Spring Boot management API calls.
- **Backend (`backend/`):** Java 21 (Virtual Threads enabled) + **Spring Boot 3.4+** (Spring Web, Spring Security OAuth2 Resource Server, Spring Data JPA, Flyway, Resilience4j, Caffeine, WebClient/RestClient). Packaged as an optimized multi-stage Docker container with **Caddy** reverse proxy (automatic HTTPS) for **Oracle Cloud Infrastructure (OCI) Always Free** (or Render/Koyeb).
- **Database & Auth:** **Supabase** (PostgreSQL 16 + `pgvector` extension + Row-Level Security + Supabase Auth).

### 1.2. Monorepo Folder Structure
Organize the project cleanly into a monorepo so both services can be developed, tested, and deployed independently:

```text
aethergate/
├── CLAUDE.md                             # This master blueprint
├── REACT_FRONTEND_VETERAN_SKILL.md       # Frontend craftsmanship rules
├── SYSTEM_DESIGN.md                      # Portfolio architecture documentation
├── .env.example                          # Template of required env vars
├── .gitignore
├── deploy/
│   ├── docker-compose.yml                # Spring Boot + Caddy HTTPS reverse proxy
│   └── Caddyfile                         # Automatic SSL & CORS headers
├── backend/
│   ├── pom.xml                           # Maven (Java 21, Spring Boot 3.4, Flyway, Resilience4j, JJWT)
│   ├── Dockerfile                        # Multi-stage Alpine JRE 21 build (-XX:MaxRAMPercentage=75.0)
│   └── src/
│       ├── main/
│       │   ├── java/com/aethergate/
│       │   │   ├── AetherGateApplication.java
│       │   │   ├── config/
│       │   │   │   ├── SecurityConfig.java           # Dual auth: JWT (Dashboard) + X-API-Key (Gateway)
│       │   │   │   ├── TenantContext.java            # Java 21 ScopedValue / ThreadLocal isolation
│       │   │   │   ├── TenantAspect.java             # Executes SET LOCAL app.current_tenant_id
│       │   │   │   └── ResilienceConfig.java         # CircuitBreaker & Retry for LLM providers
│       │   │   ├── domain/
│       │   │   │   ├── model/                        # Entities (Tenant, ApiKey, LedgerEntry, etc.)
│       │   │   │   └── repository/                   # Spring Data JPA repositories
│       │   │   ├── service/
│       │   │   │   ├── GatewayOrchestratorService.java # Auth -> RateLimit -> Cache -> LLM -> Settle
│       │   │   │   ├── MeteringLedgerService.java    # Pre-flight RESERVE & Post-flight SETTLE/RELEASE
│       │   │   │   ├── SemanticCacheService.java     # pgvector cosine similarity lookup & store
│       │   │   │   ├── RateLimiterService.java       # Token Bucket (Caffeine + DB bucket sync)
│       │   │   │   └── LlmRouterService.java         # Primary -> Fallback routing + SSE streaming
│       │   │   └── web/
│       │   │       ├── GatewayController.java        # POST /v1/chat/completions (OpenAI-compatible)
│       │   │       ├── DashboardController.java      # GET /api/v1/dashboard/* (JWT protected)
│       │   │       ├── TenantController.java         # CRUD API keys, provider configs, top-ups
│       │   │       └── GlobalExceptionHandler.java   # RFC 7807 ProblemDetail responses
│       │   └── resources/
│       │       ├── application.yml
│       │       └── db/migration/
│       │           ├── V1__init_schema_and_pgvector.sql
│       │           ├── V2__rls_and_immutability_triggers.sql
│       │           └── V3__seed_demo_tenants.sql
│       └── test/
│           └── java/com/aethergate/
│               ├── MeteringLedgerConcurrencyTest.java
│               └── SemanticCacheServiceTest.java
└── frontend/
    ├── package.json
    ├── vercel.json                       # SPA rewrite rules for React Router
    └── src/
        ├── lib/
        │   ├── supabase.ts               # Supabase Auth client
        │   ├── api.ts                    # Typed fetch wrapper injecting Bearer JWT
        │   └── format.ts                 # Tabular currency ($0.000000) & token formatters
        ├── context/
        │   └── AuthContext.tsx           # Session state, Demo Quick-Login, Tenant binding
        ├── components/
        │   ├── layout/
        │   │   ├── AppShell.tsx          # Minimalist sidebar + top context bar
        │   │   └── ErrorBoundary.tsx
        │   └── ui/                       # Crisp 1px-bordered minimalist primitives
        └── pages/
            ├── LoginPage.tsx             # Ultra-clean Login / Sign Up + "1-Click Demo Login"
            ├── OverviewPage.tsx          # Live burn-rate, TTFT, Cache Hit Ratio, Balance
            ├── LedgerAuditPage.tsx       # Immutable double-entry RESERVE/SETTLE/RELEASE log
            ├── PlaygroundChaosPage.tsx   # Live SSE streaming + Cache Hit + 503 Failover simulator
            └── ApiKeysRoutingPage.tsx    # API key generator & LLM provider fallback priority
```

---

## 2. Database Schema & Migrations (Supabase PostgreSQL)

### 2.1. Core Tables (`V1__init_schema_and_pgvector.sql`)
- Enable `CREATE EXTENSION IF NOT EXISTS vector;` and `CREATE EXTENSION IF NOT EXISTS "uuid-ossp";`.
- **`tenants`**:
  - `id UUID PRIMARY KEY`
  - `owner_user_id UUID NOT NULL` (links to Supabase `auth.users.id`)
  - `name VARCHAR(120) NOT NULL`
  - `slug VARCHAR(80) UNIQUE NOT NULL`
  - `plan_tier VARCHAR(32) NOT NULL DEFAULT 'PRO'`
  - `available_balance_usd NUMERIC(19, 8) NOT NULL DEFAULT 100.00000000`
  - `reserved_balance_usd NUMERIC(19, 8) NOT NULL DEFAULT 0.00000000`
  - `hard_budget_limit_usd NUMERIC(19, 8) NOT NULL DEFAULT 500.00000000`
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- **`api_keys`**:
  - `id UUID PRIMARY KEY`, `tenant_id UUID NOT NULL REFERENCES tenants(id)`
  - `name VARCHAR(100) NOT NULL`, `key_prefix VARCHAR(16) NOT NULL` (e.g., `ag_live_8f92`)
  - `key_hash VARCHAR(64) UNIQUE NOT NULL` (SHA-256)
  - `rate_limit_rpm INT NOT NULL DEFAULT 60`, `rate_limit_tpm INT NOT NULL DEFAULT 100000`
  - `is_active BOOLEAN NOT NULL DEFAULT TRUE`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- **`provider_configs`**:
  - `id UUID PRIMARY KEY`, `tenant_id UUID NOT NULL REFERENCES tenants(id)`
  - `provider VARCHAR(32) NOT NULL` (`OPENAI`, `ANTHROPIC`, `GEMINI`, `MOCK_LLM`)
  - `model_name VARCHAR(80) NOT NULL`, `priority_order INT NOT NULL` (`1` = primary, `2` = fallback)
  - `input_cost_per_1m NUMERIC(19, 8) NOT NULL`, `output_cost_per_1m NUMERIC(19, 8) NOT NULL`
  - `is_enabled BOOLEAN NOT NULL DEFAULT TRUE`
- **`ledger_entries` (Append-Only Double-Entry Metering)**:
  - `id UUID PRIMARY KEY`, `tenant_id UUID NOT NULL REFERENCES tenants(id)`
  - `request_id VARCHAR(64) NOT NULL`
  - `entry_type VARCHAR(20) NOT NULL` (`TOP_UP`, `RESERVE`, `SETTLE`, `RELEASE`)
  - `amount_usd NUMERIC(19, 8) NOT NULL CHECK (amount_usd >= 0)`
  - `balance_after_usd NUMERIC(19, 8) NOT NULL`
  - `description VARCHAR(255)`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
  - `UNIQUE (tenant_id, request_id, entry_type)` (Idempotency guarantee!)
- **`llm_request_logs`**:
  - `id UUID PRIMARY KEY`, `tenant_id UUID NOT NULL REFERENCES tenants(id)`
  - `request_id VARCHAR(64) UNIQUE NOT NULL`
  - `provider_used VARCHAR(32) NOT NULL`, `model_used VARCHAR(80) NOT NULL`
  - `prompt_tokens INT NOT NULL`, `completion_tokens INT NOT NULL`
  - `total_cost_usd NUMERIC(19, 8) NOT NULL`
  - `latency_ms INT NOT NULL`, `ttft_ms INT NOT NULL`
  - `cache_status VARCHAR(16) NOT NULL` (`HIT`, `MISS`)
  - `failover_triggered BOOLEAN NOT NULL DEFAULT FALSE`
  - `status_code INT NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- **`semantic_cache`**:
  - `id UUID PRIMARY KEY`, `tenant_id UUID NOT NULL REFERENCES tenants(id)`
  - `prompt_text TEXT NOT NULL`, `embedding vector(384) NOT NULL`
  - `response_text TEXT NOT NULL`, `model_name VARCHAR(80) NOT NULL`
  - `hit_count INT NOT NULL DEFAULT 0`, `expires_at TIMESTAMPTZ NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`

### 2.2. Security Triggers & RLS (`V2__rls_and_immutability_triggers.sql`)
1. **Strict Ledger Immutability Trigger:** Create a `BEFORE UPDATE OR DELETE ON ledger_entries` trigger that raises `SECURITY VIOLATION: Ledger entries are strictly append-only`.
2. **Row-Level Security (RLS):** Enable RLS on `api_keys`, `provider_configs`, `ledger_entries`, `llm_request_logs`, and `semantic_cache` using `tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid`.
3. **HNSW Vector Index:** `CREATE INDEX idx_semantic_cache_embedding ON semantic_cache USING hnsw (embedding vector_cosine_ops);`

---

## 3. Core Backend Engineering Specifications (Java 21 + Spring Boot)

### 3.1. Authentication & Security (`SecurityConfig.java`)
Configure **two distinct security filter chains**:
1. **Gateway Chain (`/v1/**`):** Stateless, authenticates via `X-API-Key` header. Hashes incoming key with SHA-256, looks up active `ApiKey` entity, binds `tenantId` into `TenantContext`, and enforces rate limits.
2. **Management Dashboard Chain (`/api/v1/**`):** Stateless OAuth2 Resource Server verifying **Supabase JWT tokens** (`spring.security.oauth2.resourceserver.jwt.jwk-set-uri=${SUPABASE_URL}/auth/v1/.well-known/jwks.json` or HMAC secret). Extracts `sub` (Supabase User UUID) and resolves/auto-provisions the user's `Tenant` on first login!
3. **CORS:** Explicitly allow `http://localhost:5173` and `${FRONTEND_ORIGIN}` (Vercel URL).

### 3.2. Pre-Flight Reserve & Post-Flight Settle (`MeteringLedgerService.java`)
- **Step 1 (`reserveBudget`):** Locks `Tenant` row (`@Lock(LockModeType.PESSIMISTIC_WRITE)`), verifies `available_balance_usd >= estimatedMaxCost`, deducts `estimatedMaxCost` from `available_balance_usd`, adds to `reserved_balance_usd`, and inserts a `RESERVE` ledger entry. Throws `InsufficientQuotaException` (HTTP `402 Payment Required`) if balance is too low.
- **Step 2 (`settleTransaction`):** Calculates exact cost using `BigDecimal` (`MathContext.DECIMAL128`, scale 8). Deducts `estimatedMaxCost` from `reserved_balance_usd`, returns `(estimatedMaxCost - actualCost)` back to `available_balance_usd`, and writes `SETTLE` + `RELEASE` entries into `ledger_entries`. Fully idempotent via `(tenant_id, request_id, entry_type)`.

### 3.3. Built-in Intelligent LLM Simulator Mode (`MOCK_LLM`)
So the portfolio works out-of-the-box without requiring the evaluator to pay for OpenAI credits:
- Support real OpenAI/Anthropic API keys **AND** a built-in realistic `MOCK_LLM` streaming engine that simulates token-by-token SSE chunks (`data: {"choices":[{"delta":{"content":"..."}}]}`), realistic TTFT delays (80–180ms), deterministic 384-dim embeddings (using a lightweight local TF-IDF / trigram hashing vectorizer in Java so Semantic Cache works 100% offline/free with `pgvector`!), and an optional header `X-Simulate-Provider-Failure: true` to trigger Resilience4j Circuit Breaker failover live in the UI.

---

## 4. Frontend Design & Login Experience (`frontend/`)

Follow **`REACT_FRONTEND_VETERAN_SKILL.md`** strictly. Aesthetic benchmark: **Linear / Stripe / Vercel Console** — ultra-clean, high-contrast minimalist UI with dark/light mode toggle, crisp `1px` hairline borders, generous whitespace, and `tabular-nums` on every financial figure.

### 4.1. Minimalist Login & Onboarding Page (`LoginPage.tsx`)
- **Split or Centered Precision Layout:** Clean geometric AetherGate emblem, concise value proposition (*"Deterministic Token Metering & Resilient AI Routing"*), email + password form powered by **Supabase Auth** (`signInWithPassword` / `signUp`), and clean inline validation states.
- **"1-Click Recruiter / Demo Login" Button:** Prominent, tasteful secondary button (*"Continue with Demo Workspace"*) that logs directly into a pre-seeded B2B tenant (*"Vortex AI Labs"*) with realistic historical metrics, ledger entries, and cached prompts so any hiring manager can explore the system immediately without creating an account.

### 4.2. Four Core Dashboard Views
1. **Overview (`/`)**:
   - 4 crisp KPI cards (`Available Credit`, `Reserved In-Flight`, `Semantic Cache Savings`, `Avg TTFT Latency`).
   - Live Recharts area/bar chart of Token Consumption & Cost Burn Rate (USD) over time.
   - Active Circuit Breaker status pill (`OPENAI: CLOSED` / `ANTHROPIC: STANDBY`).
2. **AI Playground & Chaos Console (`/playground`)**:
   - Interactive prompt terminal with real-time token-by-token streaming output.
   - Live telemetry drawer showing: `Request ID`, `Provider Used`, `Cache Status (HIT/MISS)`, `Pre-Flight Reserved`, `Post-Flight Settled`, `Refunded Dust`, and `TTFT (ms)`.
   - Quick-action chaos buttons: *"Send Prompt"*, *"Re-send Same Prompt (Trigger Semantic Cache HIT)"*, and *"Simulate Primary 503 Outage (Test Resilience4j Failover)"*.
3. **Immutable Ledger (`/ledger`)**:
   - Filterable double-entry audit table displaying `RESERVE`, `SETTLE`, `RELEASE`, and `TOP_UP` entries in `font-mono tabular-nums` with 8-decimal micro-cent precision (`$0.00142000`) and a *"Top Up +$50.00"* action button.
4. **API Keys & Routing (`/settings`)**:
   - Generate new `ag_live_...` API keys (shown once in a copyable modal), revoke keys, and reorder LLM provider fallback priority (`Priority 1 -> Priority 2`).

---

## 5. Step-by-Step Execution Protocol for Claude Code

Execute the project in these exact phases, verifying each step before proceeding:

1. **Phase 1 — Backend Foundation & Migrations:**
   - Scaffold `backend/` with Maven (`pom.xml`), Java 21, `spring.threads.virtual.enabled=true`, Flyway migrations (`V1`, `V2`, `V3`), and JPA entities.
   - Verify compilation with `mvn test-compile`.
2. **Phase 2 — Core Engine & Concurrency Tests:**
   - Implement `MeteringLedgerService`, `SemanticCacheService`, `RateLimiterService`, `LlmRouterService`, `SecurityConfig`, and REST controllers.
   - Write and run JUnit 5 tests verifying that 20 concurrent requests cannot overdraft a tenant's `available_balance_usd` and that `ledger_entries` rejects `UPDATE`/`DELETE`.
3. **Phase 3 — Minimalist React Frontend & Login:**
   - Scaffold `frontend/` with Vite + React + TypeScript + Tailwind CSS.
   - Build `LoginPage.tsx` (Supabase Auth + Demo Workspace mode), `AppShell.tsx`, and all 4 dashboard pages adhering to `REACT_FRONTEND_VETERAN_SKILL.md`.
   - Verify TypeScript and production bundle with `npm run build`.
4. **Phase 4 — Deployment Artifacts & Verification:**
   - Create `deploy/docker-compose.yml`, `deploy/Caddyfile`, `backend/Dockerfile`, and `frontend/vercel.json`.
   - Provide clear final instructions for running Flyway against Supabase, deploying `frontend/` to Vercel (`vercel --prod`), and launching `backend/` on Oracle Cloud Always Free / Render.