import { supabase } from './supabase'

export const API_BASE = ((import.meta.env.VITE_API_BASE_URL as string | undefined) ?? 'http://localhost:8080').replace(/\/$/, '')

/** RFC 7807 problem returned by the gateway. */
export class ApiError extends Error {
  readonly status: number
  readonly type: string
  readonly title: string
  readonly body: Record<string, unknown>

  constructor(status: number, body: Record<string, unknown>) {
    super(String(body.detail ?? body.title ?? `HTTP ${status}`))
    this.status = status
    this.type = String(body.type ?? 'about:blank')
    this.title = String(body.title ?? 'Request failed')
    this.body = body
  }
}

async function authHeader(): Promise<Record<string, string>> {
  const { data } = await supabase.auth.getSession()
  const token = data.session?.access_token
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function toError(res: Response): Promise<ApiError> {
  let body: Record<string, unknown> = {}
  try {
    body = await res.json()
  } catch {
    body = { title: res.statusText }
  }
  return new ApiError(res.status, body)
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let res: Response
  try {
    res = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...(await authHeader()), ...(init.headers ?? {}) },
    })
  } catch {
    throw new ApiError(0, { title: 'Gateway unreachable', detail: `Cannot reach the AetherGate API at ${API_BASE}.` })
  }
  if (!res.ok) throw await toError(res)
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

export const get = <T,>(path: string) => request<T>(path)
export const post = <T,>(path: string, body?: unknown) =>
  request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) })
export const put = <T,>(path: string, body: unknown) => request<T>(path, { method: 'PUT', body: JSON.stringify(body) })
export const patch = <T,>(path: string, body: unknown) => request<T>(path, { method: 'PATCH', body: JSON.stringify(body) })
export const del = <T,>(path: string) => request<T>(path, { method: 'DELETE' })
/** Conditional replace: the server answers 412 if `version` is no longer current (another tab saved first). */
export const putIfMatch = <T,>(path: string, version: number, body: unknown) =>
  request<T>(path, { method: 'PUT', headers: { 'If-Match': `"${version}"` }, body: JSON.stringify(body) })

// ── API types (snake_case mirrors the backend; money is a decimal string) ──────────────

export type Money = string

export interface TenantRef {
  id: string
  name: string
  slug: string
  plan_tier: string
  demo: boolean
}

export interface Me {
  user_id: string
  email: string
  tenant: TenantRef
}

export interface Kpis {
  available_balance_usd: Money
  reserved_balance_usd: Money
  hard_budget_limit_usd: Money
  month_to_date_spend_usd: Money
  spend_usd: Money
  cache_savings_usd: Money
  requests: number
  cache_hits: number
  cache_hit_ratio: number
  failovers: number
  prompt_tokens: number
  completion_tokens: number
  avg_ttft_miss_ms: number | null
  avg_ttft_hit_ms: number | null
}

export interface SeriesPoint {
  bucket: string
  prompt_tokens: number
  completion_tokens: number
  cost_usd: Money
  saved_usd: Money
  requests: number
  cache_hits: number
}

export interface ProviderStatus {
  provider: string
  model_name: string
  priority_order: number
  enabled: boolean
  live: boolean
  circuit_state: 'CLOSED' | 'OPEN' | 'HALF_OPEN' | string
}

export interface RequestRow {
  request_id: string
  provider_used: string
  model_used: string
  prompt_tokens: number
  completion_tokens: number
  total_cost_usd: Money
  saved_cost_usd: Money
  latency_ms: number
  ttft_ms: number
  cache_status: 'HIT' | 'MISS'
  failover_triggered: boolean
  status_code: number
  created_at: string
}

export interface Overview {
  tenant: TenantRef
  range: string
  kpis: Kpis
  series: SeriesPoint[]
  providers: ProviderStatus[]
  isolation: { other_tenant_rows_in_database: number; other_tenant_rows_visible: number }
  recent_requests: RequestRow[]
}

export type EntryType = 'TOP_UP' | 'RESERVE' | 'SETTLE' | 'RELEASE'

export interface LedgerRow {
  id: string
  request_id: string
  entry_type: EntryType
  amount_usd: Money
  balance_after_usd: Money
  description: string | null
  created_at: string
}

export interface LedgerPage {
  entries: LedgerRow[]
  page: number
  size: number
  total_elements: number
  total_pages: number
  totals_by_type: Record<EntryType, Money>
  balanced: boolean
}

export interface ApiKeyView {
  id: string
  name: string
  key_prefix: string
  rate_limit_rpm: number
  rate_limit_tpm: number
  active: boolean
  last_used_at: string | null
  created_at: string
  project_id: string | null
}

export type ProjectStatus = 'ACTIVE' | 'ARCHIVED'

/** committed = month-to-date settled + holds in flight: the number the pre-flight check compares to the budget. */
export interface ProjectView {
  id: string
  name: string
  description: string | null
  status: ProjectStatus
  monthly_budget_usd: Money | null
  month_to_date_usd: Money
  open_holds_usd: Money
  committed_usd: Money
  remaining_usd: Money | null
  budget_utilization: number | null
  requests30d: number
  cache_hits30d: number
  cost30d_usd: Money
  active_keys: number
  version: number
  created_at: string
  updated_at: string
  archived_at: string | null
}

export interface ProjectList {
  projects: ProjectView[]
  unassigned: { month_to_date_usd: Money; requests30d: number; cost30d_usd: Money }
  max_active_projects: number
}

export interface DailyUsage {
  day: string
  requests: number
  cache_hits: number
  cost_usd: Money
}

export interface ProjectDetail {
  project: ProjectView
  daily: DailyUsage[]
  keys: { id: string; name: string; key_prefix: string; active: boolean }[]
}

export interface ArchiveResult {
  project: ProjectView
  revoked_keys: number
}

export interface ProviderView {
  id: string
  provider: string
  model_name: string
  priority_order: number
  input_cost_per1m: Money
  output_cost_per1m: Money
  enabled: boolean
  live: boolean
}

export interface Telemetry {
  request_id: string
  cache_status: 'HIT' | 'MISS'
  provider: string
  model: string
  live: boolean
  prompt_tokens: number
  completion_tokens: number
  reserved_usd: Money
  settled_usd: Money
  released_usd: Money
  saved_usd: Money
  ttft_ms: number
  latency_ms: number
  failover_triggered: boolean
  attempts: string[]
  finish_reason: string
  cache_similarity: number | null
  redactions: number
}

export type GuardrailType = 'KEYWORDS' | 'REGEX' | 'PII_EMAIL' | 'PII_CARD'
export type GuardrailAction = 'BLOCK' | 'REDACT'

export interface GuardrailView {
  id: string
  name: string
  type: GuardrailType
  pattern: string | null
  action: GuardrailAction
  enabled: boolean
  hits30d: number
  last_hit_at: string | null
  version: number
  created_at: string
  updated_at: string
}

export interface GuardrailList {
  rules: GuardrailView[]
  recent_events: { rule_name: string; action: GuardrailAction; request_id: string; matches: number; created_at: string }[]
  max_rules: number
}

export interface GuardrailTestResult {
  blocked: boolean
  blocked_by: string | null
  result_text: string
  matches: { rule: string; action: GuardrailAction; count: number }[]
}

export interface WorkspaceSettings {
  name: string
  slug: string
  plan_tier: string
  demo: boolean
  hard_budget_limit_usd: Money
  month_to_date_usd: Money
  reserved_usd: Money
  committed_usd: Money
  available_balance_usd: Money
  created_at: string
}

// ── Streaming (SSE over fetch: EventSource cannot POST or send headers) ────────────────

export interface StreamHandlers {
  onDelta: (text: string) => void
  onTelemetry: (t: Telemetry) => void
  onError: (e: ApiError) => void
}

export async function streamPlayground(
  body: { prompt: string; system?: string; max_tokens?: number; simulate_failure?: boolean; project_id?: string | null },
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  let res: Response
  try {
    res = await fetch(`${API_BASE}/api/v1/playground/completions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(await authHeader()) },
      body: JSON.stringify(body),
      signal,
    })
  } catch (e) {
    if ((e as Error).name === 'AbortError') return
    handlers.onError(new ApiError(0, { title: 'Gateway unreachable', detail: `Cannot reach ${API_BASE}` }))
    return
  }
  if (!res.ok || !res.body) {
    handlers.onError(await toError(res))
    return
  }
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let sep: number
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      dispatch(frame, handlers)
    }
  }
}

function dispatch(frame: string, h: StreamHandlers) {
  let event = 'message'
  let data = ''
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data += line.slice(5).trim()
  }
  if (!data || data === '[DONE]') return
  const json = JSON.parse(data)
  if (event === 'aethergate.telemetry') h.onTelemetry(json as Telemetry)
  else if (event === 'error') h.onError(new ApiError(json.status ?? 500, json))
  else {
    const delta = json.choices?.[0]?.delta?.content
    if (delta) h.onDelta(delta)
  }
}
