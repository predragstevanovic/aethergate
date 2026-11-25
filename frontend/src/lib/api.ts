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

const WORKSPACE_KEY = 'aethergate.workspace'

/** The workspace the console acts in (sent as X-Workspace-Id); null = the user's default workspace. */
export function selectedWorkspace(): string | null {
  try {
    return localStorage.getItem(WORKSPACE_KEY)
  } catch {
    return null
  }
}

export function selectWorkspace(id: string | null) {
  try {
    if (id) localStorage.setItem(WORKSPACE_KEY, id)
    else localStorage.removeItem(WORKSPACE_KEY)
  } catch {
    /* private mode: the default workspace is used */
  }
}

async function authHeader(): Promise<Record<string, string>> {
  const { data } = await supabase.auth.getSession()
  const token = data.session?.access_token
  const ws = selectedWorkspace()
  return { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(ws ? { 'X-Workspace-Id': ws } : {}) }
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

export type MemberRole = 'OWNER' | 'ADMIN' | 'DEVELOPER' | 'VIEWER'

export const ROLE_RANK: Record<MemberRole, number> = { VIEWER: 1, DEVELOPER: 2, ADMIN: 3, OWNER: 4 }

export interface WorkspaceSummary {
  id: string
  name: string
  slug: string
  role: MemberRole
  demo: boolean
  primary_owner: boolean
}

export interface Me {
  user_id: string
  email: string
  tenant: TenantRef
  role: MemberRole
  workspace_fallback: boolean
  workspaces: WorkspaceSummary[]
}

export interface MemberView {
  id: string | null
  user_id: string | null
  email: string | null
  role: MemberRole
  status: 'ACTIVE' | 'INVITED'
  primary_owner: boolean
  you: boolean
  joined_at: string | null
  invite_expires_at: string | null
  invited_by: string | null
  version: number
}

export interface TeamList {
  members: MemberView[]
  your_role: MemberRole
  max_members: number
  assignable_roles: MemberRole[]
}

export interface CreatedInvitation {
  member: MemberView
  token: string
}

export interface AuditEntry {
  actor_email: string | null
  actor_role: MemberRole | null
  action: string
  target_id: string | null
  status_code: number
  created_at: string
}

export interface WebhookEndpoint {
  id: string
  name: string
  url: string
  event_types: string[]
  enabled: boolean
  secret_hint: string
  version: number
  deliveries24h: number
  failed24h: number
  last_status: 'PENDING' | 'SUCCEEDED' | 'DEAD' | null
  last_delivery_at: string | null
  created_at: string
}

export interface WebhookList {
  endpoints: WebhookEndpoint[]
  event_types: string[]
  max_endpoints: number
}

export interface EndpointWithSecret {
  endpoint: WebhookEndpoint
  secret: string
}

export interface WebhookDelivery {
  id: string
  event_id: string
  event_type: string
  status: 'PENDING' | 'SUCCEEDED' | 'DEAD'
  attempts: number
  last_status_code: number | null
  last_error: string | null
  created_at: string
  next_attempt_at: string | null
  delivered_at: string | null
  payload: string
}

export interface AlertRule {
  id: string
  project_id: string | null
  scope: string
  threshold_pct: number
  enabled: boolean
  spend_usd: Money
  limit_usd: Money | null
  used_pct: number
  fired_this_month_at: string | null
}

export interface AlertList {
  rules: AlertRule[]
  recent: { scope: string; threshold_pct: number; spend_usd: Money; limit_usd: Money; fired_at: string }[]
  period: string
}

export interface PromptTemplate {
  id: string
  name: string
  description: string | null
  active_version: number
  latest_version: number
  variables: string[]
  requests30d: number
  cost30d: Money
  version: number
  updated_at: string
}

export interface PromptVersion {
  version: number
  system_prompt: string | null
  user_template: string
  variables: string[]
  note: string | null
  created_by: string | null
  created_at: string
  active: boolean
  requests: number
  avg_cost_usd: Money
  avg_prompt_tokens: number
  avg_completion_tokens: number
  avg_latency_ms: number
  cache_hit_rate: number
}

export interface PromptDetail {
  template: PromptTemplate
  versions: PromptVersion[]
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
  status: 'ACTIVE' | 'EXPIRED' | 'REVOKED'
  expires_at: string | null
  allowed_models: string[]
  ip_allowlist: string[]
  rotated_from: string | null
  version: number
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

export interface TemplateInvocation {
  id: string
  version?: number
  variables: Record<string, string>
}

export async function streamPlayground(
  body: { prompt?: string; system?: string; max_tokens?: number; simulate_failure?: boolean; project_id?: string | null;
    template?: TemplateInvocation | null },
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
