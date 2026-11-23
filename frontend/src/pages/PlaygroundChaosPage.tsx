import { Repeat, Send, ServerCrash, Square } from 'lucide-react'
import { useCallback, useRef, useState } from 'react'
import { Badge, Button, CircuitPill, ErrorBanner, PageHeader, Panel, Skeleton, inputClass } from '../components/ui/primitives'
import { useResource } from '../hooks/useResource'
import { ApiError, get, streamPlayground, type Overview, type ProjectList, type Telemetry } from '../lib/api'
import { int, ms, usd8 } from '../lib/format'

const SUGGESTIONS = [
  'Explain row level security for our onboarding docs',
  'How does the metering ledger reserve budget?',
  'What is a semantic cache and when does it help?',
  'How do circuit breakers handle an upstream outage?',
]

type Mode = 'send' | 'resend' | 'chaos'

export function PlaygroundChaosPage() {
  const [prompt, setPrompt] = useState(SUGGESTIONS[0])
  const [lastPrompt, setLastPrompt] = useState<string | null>(null)
  const [output, setOutput] = useState('')
  const [streaming, setStreaming] = useState<Mode | null>(null)
  const [telemetry, setTelemetry] = useState<Telemetry | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [history, setHistory] = useState<Telemetry[]>([])
  const abort = useRef<AbortController | null>(null)
  const health = useResource(() => get<Overview>('/api/v1/dashboard/overview?range=24h'), [])
  const projectList = useResource(() => get<ProjectList>('/api/v1/tenant/projects'), [])
  const activeProjects = (projectList.data?.projects ?? []).filter((p) => p.status === 'ACTIVE')
  const [projectId, setProjectId] = useState('')

  const run = useCallback(async (mode: Mode) => {
    const text = (mode === 'resend' ? lastPrompt : prompt)?.trim()
    if (!text || streaming) return
    abort.current = new AbortController()
    setStreaming(mode)
    setOutput('')
    setTelemetry(null)
    setError(null)
    setLastPrompt(text)
    await streamPlayground(
      { prompt: text, max_tokens: 400, simulate_failure: mode === 'chaos', project_id: projectId || null },
      {
        onDelta: (d) => setOutput((o) => o + d),
        onTelemetry: (t) => {
          setTelemetry(t)
          setHistory((h) => [t, ...h].slice(0, 8))
        },
        onError: setError,
      },
      abort.current.signal,
    )
    setStreaming(null)
    health.reload()
  }, [prompt, lastPrompt, streaming, health, projectId])

  const providers = health.data?.providers ?? []
  const primary = providers.find((p) => p.enabled)

  return (
    <div className="space-y-8">
      <PageHeader
        title="AI Playground & Chaos Console"
        description="Runs the real gateway pipeline as this workspace: rate limit → guardrails → semantic cache → pre-flight hold → routed stream → settlement. Without API keys, providers are served by the built-in deterministic simulator."
      />

      <div className="flex flex-wrap items-center gap-x-6 gap-y-3 rounded-lg border border-line bg-panel px-5 py-3.5">
        <span className="text-[12px] text-ink-3">Routing</span>
        {health.loading ? <Skeleton className="h-5 w-64" /> : providers.map((p) => (
          <CircuitPill key={p.provider + p.priority_order} provider={p.provider} state={p.circuit_state}
            primary={p === primary} enabled={p.enabled} live={p.live} />
        ))}
        <label className="ml-auto flex items-center gap-2 text-[12px] text-ink-3 whitespace-nowrap">
          Charge to
          <select aria-label="Project to charge" className={`${inputClass} h-8 w-48 text-[13px]`} value={projectId} onChange={(e) => setProjectId(e.target.value)}>
            <option value="">Unassigned</option>
            {activeProjects.map((p) => <option key={p.id} value={p.id}>{p.name}{p.monthly_budget_usd ? ` (${Math.round((p.budget_utilization ?? 0) * 100)}% of budget)` : ''}</option>)}
          </select>
        </label>
        <span className="text-[12px] text-ink-3">Breaker: 4 failures · 30 s cool-down</span>
      </div>

      <div className="grid gap-4 xl:grid-cols-[1fr_340px]">
        <div className="space-y-4">
          <Panel title="Prompt">
            <label htmlFor="prompt" className="sr-only">Prompt</label>
            <textarea
              id="prompt"
              rows={3}
              value={prompt}
              maxLength={8000}
              onChange={(e) => setPrompt(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) void run('send')
              }}
              className={`${inputClass} h-auto py-2.5 resize-y font-mono text-[13px] leading-relaxed`}
            />
            <div className="mt-3 flex flex-wrap gap-1.5">
              {SUGGESTIONS.map((s) => (
                <button key={s} type="button" onClick={() => setPrompt(s)}
                  className="rounded-full border border-line px-2.5 h-7 text-[12px] text-ink-3 hover:text-ink hover:border-line-strong">
                  {s}
                </button>
              ))}
            </div>
            <div className="mt-5 flex flex-wrap gap-2">
              <Button variant="primary" icon={<Send className="size-4" />} loading={streaming === 'send'} disabled={!!streaming || !prompt.trim()} onClick={() => void run('send')}>
                Send prompt
              </Button>
              <Button icon={<Repeat className="size-4" />} loading={streaming === 'resend'} disabled={!!streaming || !lastPrompt} onClick={() => void run('resend')}>
                Re-send same prompt
              </Button>
              <Button variant="danger" icon={<ServerCrash className="size-4" />} loading={streaming === 'chaos'} disabled={!!streaming || !prompt.trim()} onClick={() => void run('chaos')}>
                Simulate primary 503
              </Button>
              {streaming && (
                <Button variant="ghost" icon={<Square className="size-3.5" />} onClick={() => abort.current?.abort()}>Stop</Button>
              )}
            </div>
            <p className="mt-3 text-[12px] text-ink-3">
              Re-send hits the semantic cache (≥ 0.96 cosine similarity) at $0. The chaos button makes the primary
              return HTTP 503; Resilience4j retries once and fails over before the first token, and repeated outages open the breaker.
              A prompt that is already cached is answered before routing, so chaos only shows on a cache miss.
            </p>
          </Panel>

          {error && <ErrorBanner error={error} />}

          <Panel title="Streamed response" action={telemetry && <Badge tone={telemetry.cache_status === 'HIT' ? 'good' : 'neutral'}>{telemetry.cache_status === 'HIT' ? 'CACHE HIT' : 'CACHE MISS'}</Badge>}>
            <div aria-live="polite" className={`min-h-40 whitespace-pre-wrap text-[14px] leading-relaxed ${streaming ? 'caret' : ''}`}>
              {output || (!streaming && <span className="text-ink-3">The completion streams here token by token.</span>)}
            </div>
          </Panel>
        </div>

        <aside aria-label="Request telemetry" className="space-y-4">
          <Panel title="Live telemetry">
            {!telemetry ? (
              streaming ? <div className="space-y-2.5">{Array.from({ length: 7 }, (_, i) => <Skeleton key={i} className="h-5" />)}</div>
                : <p className="text-[13px] text-ink-3">Send a prompt to inspect its metering.</p>
            ) : (
              <dl className="space-y-2.5 text-[13px]">
                <Row label="Request ID" value={<span className="font-mono text-[12px]">{telemetry.request_id}</span>} />
                <Row label="Provider used" value={
                  <span className="flex items-center gap-2">
                    {telemetry.provider}
                    {telemetry.failover_triggered && <Badge tone="warn">failover</Badge>}
                  </span>} />
                <Row label="Model" value={<span className="font-mono text-[12px]">{telemetry.model}</span>} />
                <Row label="Cache status" value={<Badge tone={telemetry.cache_status === 'HIT' ? 'good' : 'neutral'}>{telemetry.cache_status}</Badge>} />
                {telemetry.redactions > 0 && <Row label="Guardrail redactions" value={<Badge tone="warn">{telemetry.redactions} masked</Badge>} />}
                {telemetry.cache_similarity != null && <Row label="Similarity" value={<span className="font-mono num">{telemetry.cache_similarity.toFixed(4)}</span>} />}
                <div className="border-t border-line my-3" />
                <Row label="Pre-flight reserved" value={<Money v={telemetry.reserved_usd} />} />
                <Row label="Post-flight settled" value={<Money v={telemetry.settled_usd} strong />} />
                <Row label="Refunded dust" value={<Money v={telemetry.released_usd} />} />
                {telemetry.cache_status === 'HIT' && <Row label="Saved by cache" value={<span className="text-good"><Money v={telemetry.saved_usd} /></span>} />}
                <div className="border-t border-line my-3" />
                <Row label="TTFT" value={<span className="font-mono num">{ms(telemetry.ttft_ms)}</span>} />
                <Row label="Total latency" value={<span className="font-mono num">{ms(telemetry.latency_ms)}</span>} />
                <Row label="Tokens in / out" value={<span className="font-mono num">{int(telemetry.prompt_tokens)} / {int(telemetry.completion_tokens)}</span>} />
                {telemetry.attempts.length > 0 && (
                  <div className="pt-2">
                    <dt className="text-[12px] text-ink-3 mb-1">Routing attempts</dt>
                    {telemetry.attempts.map((a, i) => <dd key={i} className="font-mono text-[11px] text-warn break-words">{a}</dd>)}
                  </div>
                )}
              </dl>
            )}
          </Panel>
          {history.length > 1 && (
            <Panel title="This session">
              <ul className="space-y-1.5 font-mono text-[12px] num">
                {history.map((h) => (
                  <li key={h.request_id} className="flex justify-between gap-3">
                    <span className="text-ink-3">{h.cache_status === 'HIT' ? 'HIT ' : 'MISS'} {h.provider.slice(0, 9)}</span>
                    <span>{h.cache_status === 'HIT' ? '$0' : usd8(h.settled_usd)} · {h.ttft_ms}ms</span>
                  </li>
                ))}
              </ul>
            </Panel>
          )}
        </aside>
      </div>
    </div>
  )
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <dt className="text-ink-3">{label}</dt>
      <dd className="text-right text-ink min-w-0 truncate">{value}</dd>
    </div>
  )
}

function Money({ v, strong }: { v: string; strong?: boolean }) {
  return <span className={`font-mono num ${strong ? 'text-ink font-medium' : ''}`}>{usd8(v)}</span>
}
