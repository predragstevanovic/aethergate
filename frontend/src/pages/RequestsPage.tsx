import { ListFilter, X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { Badge, Button, EmptyState, ErrorBanner, Modal, PageHeader, Panel, Segmented, Skeleton, inputClass } from '../components/ui/primitives'
import { useResource } from '../hooks/useResource'
import { get, type ExplorerRow, type Range, type RequestPage } from '../lib/api'
import { dateTime, int, ms, relative, tokens, usd8, usdSmart } from '../lib/format'

const RANGES: { value: Range; label: string }[] = [
  { value: '24h', label: '24h' }, { value: '7d', label: '7d' }, { value: '30d', label: '30d' }, { value: '90d', label: '90d' },
]
const TEXT_FILTERS = [
  { key: 'end_user', label: 'End user', placeholder: 'acme-corp' },
  { key: 'session', label: 'Session', placeholder: 'agent-run-1' },
  { key: 'tag', label: 'Tag', placeholder: 'feature:search' },
  { key: 'model', label: 'Model', placeholder: 'gpt-4o' },
  { key: 'q', label: 'Request id', placeholder: 'req_…' },
] as const
const FILTER_KEYS = ['model', 'provider', 'project', 'api_key', 'end_user', 'session', 'tag', 'cache', 'status', 'routing', 'q']
const PAGE = 25

export function RequestsPage() {
  const [params, setParams] = useSearchParams()
  const range = (RANGES.some((r) => r.value === params.get('range')) ? params.get('range') : '7d') as Range
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const active: [string, string][] = FILTER_KEYS.filter((k) => params.get(k)).map((k) => [k, params.get(k)!])
  const qs = new URLSearchParams([...active, ['range', range], ['page', String(page)], ['size', String(PAGE)]]).toString()
  const res = useResource(() => get<RequestPage>(`/api/v1/analytics/requests?${qs}`), [qs], 15_000)
  const [draft, setDraft] = useState<Record<string, string>>(() => Object.fromEntries(TEXT_FILTERS.map((f) => [f.key, params.get(f.key) ?? ''])))
  const [inspect, setInspect] = useState<ExplorerRow | null>(null)
  const d = res.data

  const update = (patch: Record<string, string | null>) => setParams((p) => {
    Object.entries(patch).forEach(([k, v]) => (v ? p.set(k, v) : p.delete(k)))
    if (!('page' in patch)) p.delete('page')
    return p
  }, { replace: true })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    update(Object.fromEntries(TEXT_FILTERS.map((f) => [f.key, draft[f.key]?.trim() || null])))
  }

  return (
    <div className="space-y-8">
      <PageHeader
        title="Request explorer"
        description="Every request with its cost, tokens, latency, cache and routing outcome, and who it was for. Filter by end user, session, cost tag, model, cache or errors; every showback row links here."
        actions={<Segmented label="Time range" value={range} options={RANGES} onChange={(v) => update({ range: v })} />}
      />
      <Panel pad={false} title={<span className="inline-flex items-center gap-2"><ListFilter className="size-4" aria-hidden />Requests</span>}
        action={d && <span className="text-[12px] text-ink-3 num">{int(d.total)} matching · {usdSmart(d.cost_usd)} · {tokens(d.tokens)} tokens</span>}>
        <form onSubmit={submit} className="flex flex-wrap items-end gap-2 p-4 border-b border-line">
          {TEXT_FILTERS.map((f) => (
            <label key={f.key} className="text-[12px] text-ink-3 space-y-1">
              <span className="block">{f.label}</span>
              <input className={`${inputClass} h-8 w-36 text-[13px]`} placeholder={f.placeholder} value={draft[f.key] ?? ''}
                onChange={(e) => setDraft((cur) => ({ ...cur, [f.key]: e.target.value }))} />
            </label>
          ))}
          <label className="text-[12px] text-ink-3 space-y-1">
            <span className="block">Cache</span>
            <select className={`${inputClass} h-8 w-24 text-[13px]`} value={params.get('cache') ?? ''} onChange={(e) => update({ cache: e.target.value || null })}>
              <option value="">Any</option><option value="HIT">HIT</option><option value="MISS">MISS</option>
            </select>
          </label>
          <label className="text-[12px] text-ink-3 space-y-1">
            <span className="block">Status</span>
            <select className={`${inputClass} h-8 w-24 text-[13px]`} value={params.get('status') ?? ''} onChange={(e) => update({ status: e.target.value || null })}>
              <option value="">Any</option><option value="ok">OK</option><option value="error">Errors</option>
            </select>
          </label>
          <label className="text-[12px] text-ink-3 space-y-1">
            <span className="block">Routing</span>
            <select className={`${inputClass} h-8 w-32 text-[13px]`} value={params.get('routing') ?? ''} onChange={(e) => update({ routing: e.target.value || null })}>
              <option value="">Any</option><option value="DOWNGRADE">Downgraded</option>
            </select>
          </label>
          <Button type="submit" size="sm" variant="primary">Apply</Button>
        </form>
        {active.length > 0 && (
          <div className="flex flex-wrap items-center gap-1.5 px-4 py-2 border-b border-line">
            {active.map(([k, v]) => (
              <button key={k} type="button" onClick={() => { setDraft((cur) => ({ ...cur, [k]: '' })); update({ [k]: null }) }}
                className="inline-flex items-center gap-1 rounded border border-line bg-panel-2 px-1.5 h-6 text-[12px] hover:border-line-strong"
                aria-label={`Remove filter ${k}`}>
                <span className="text-ink-3">{k}</span><span className="font-mono">{v}</span><X className="size-3" aria-hidden />
              </button>
            ))}
          </div>
        )}
        {res.error && <div className="p-5"><ErrorBanner error={res.error} onRetry={res.reload} /></div>}
        {!d ? <div className="p-5 space-y-2">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-9" />)}</div>
          : !d.rows.length ? <EmptyState title="No requests match" hint="Widen the time range or remove a filter." />
          : (
            <div className="overflow-x-auto">
              <table className="w-full text-[13px]">
                <thead><tr className="text-left text-[12px] text-ink-3 border-b border-line">
                  <th className="font-normal px-5 h-9">When</th><th className="font-normal px-3">Model</th>
                  <th className="font-normal px-3">Who</th><th className="font-normal px-3">Tags</th>
                  <th className="font-normal px-3 text-right">Tokens</th><th className="font-normal px-3 text-right">Cost</th>
                  <th className="font-normal px-3 text-right">Latency</th><th className="font-normal px-5">Outcome</th>
                </tr></thead>
                <tbody>
                  {d.rows.map((r) => (
                    <tr key={r.request_id} className="border-b border-line last:border-0 hover:bg-panel-2/50 cursor-pointer" onClick={() => setInspect(r)}>
                      <td className="px-5 py-2 whitespace-nowrap text-ink-2" title={dateTime(r.created_at)}>{relative(r.created_at)}</td>
                      <td className="px-3 font-mono text-[12px] whitespace-nowrap">{r.model}{r.routing_reason && <span className="ml-1.5"><Badge tone="warn">downgraded</Badge></span>}</td>
                      <td className="px-3 text-[12px] text-ink-2 max-w-48 truncate">
                        {r.end_user_id ?? <span className="text-ink-3">anonymous</span>}{r.session_id && <span className="text-ink-3"> · {r.session_id}</span>}
                      </td>
                      <td className="px-3"><span className="flex flex-wrap gap-1">{Object.entries(r.tags).slice(0, 3).map(([k, v]) => <Badge key={k} mono>{k}={v}</Badge>)}</span></td>
                      <td className="px-3 text-right num text-ink-2 whitespace-nowrap">{tokens(r.prompt_tokens)}→{tokens(r.completion_tokens)}</td>
                      <td className="px-3 text-right font-mono num">{usdSmart(r.cost_usd)}</td>
                      <td className="px-3 text-right num text-ink-2">{ms(r.latency_ms)}</td>
                      <td className="px-5 whitespace-nowrap">
                        {r.status_code >= 400 ? <Badge tone="bad">HTTP {r.status_code}</Badge> : r.cache_status === 'HIT' ? <Badge tone="good">cache HIT</Badge> : <Badge>MISS</Badge>}
                        {r.failover && <span className="ml-1"><Badge tone="warn">failover</Badge></span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        {d && d.total > PAGE && (
          <div className="flex items-center justify-between px-5 py-3 border-t border-line text-[12px] text-ink-3">
            <span className="num">{page * PAGE + 1}–{Math.min(d.total, (page + 1) * PAGE)} of {int(d.total)}</span>
            <span className="flex gap-1">
              <Button size="sm" variant="ghost" disabled={page === 0} onClick={() => update({ page: String(page - 1) })}>Previous</Button>
              <Button size="sm" variant="ghost" disabled={(page + 1) * PAGE >= d.total} onClick={() => update({ page: String(page + 1) })}>Next</Button>
            </span>
          </div>
        )}
      </Panel>
      <Modal wide open={inspect !== null} title={`Request · ${inspect?.request_id ?? ''}`} onClose={() => setInspect(null)}>
        {inspect && (
          <dl className="grid grid-cols-[10rem_1fr] gap-x-4 gap-y-1.5 text-[13px]">
            {([
              ['Time', dateTime(inspect.created_at)],
              ['Provider · model', `${inspect.provider} · ${inspect.model}`],
              ['Requested model', inspect.requested_model ?? '—'],
              ['Routing', inspect.routing_reason ?? 'chain order'],
              ['Project', inspect.project_name ?? 'Unassigned'],
              ['API key', inspect.api_key_prefix],
              ['End user', inspect.end_user_id ?? '—'],
              ['Session', inspect.session_id ?? '—'],
              ['Tags', Object.entries(inspect.tags).map(([k, v]) => `${k}=${v}`).join(', ') || '—'],
              ['Tokens', `${int(inspect.prompt_tokens)} prompt / ${int(inspect.completion_tokens)} completion`],
              ['Cost (settled)', usd8(inspect.cost_usd)],
              ['Saved by cache', usd8(inspect.saved_usd)],
              ['Latency · TTFT', `${ms(inspect.latency_ms)} · ${ms(inspect.ttft_ms)}`],
              ['Outcome', `HTTP ${inspect.status_code} · cache ${inspect.cache_status}${inspect.failover ? ' · failover' : ''}`],
              ['Prompt template', inspect.template_version ? `v${inspect.template_version}` : '—'],
            ] as const).map(([k, v]) => (
              <div key={k} className="contents"><dt className="text-ink-3">{k}</dt><dd className="font-mono text-[12px] break-all">{v}</dd></div>
            ))}
          </dl>
        )}
      </Modal>
    </div>
  )
}
