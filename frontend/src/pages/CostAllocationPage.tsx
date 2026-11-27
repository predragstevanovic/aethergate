import { Download, PieChart, Users } from 'lucide-react'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { Badge, Button, EmptyState, ErrorBanner, PageHeader, Panel, Segmented, Skeleton, inputClass } from '../components/ui/primitives'
import { useAsyncAction } from '../hooks/useInteractions'
import { useResource } from '../hooks/useResource'
import { download, get, type Attribution, type EndUsers, type Range } from '../lib/api'
import { int, ms, percent, relative, tokens, usd, usdSmart } from '../lib/format'

const RANGES: { value: Range; label: string }[] = [
  { value: '24h', label: '24h' }, { value: '7d', label: '7d' }, { value: '30d', label: '30d' }, { value: '90d', label: '90d' },
]
const DIMENSIONS = [
  { value: 'project', label: 'Project' }, { value: 'model', label: 'Model' }, { value: 'provider', label: 'Provider' },
  { value: 'api_key', label: 'API key' }, { value: 'end_user', label: 'End user' }, { value: 'tag', label: 'Cost tag' },
] as const

/** Explorer filter for a row of a given dimension (so every showback row links to its requests). */
function explorerLink(dimension: string, key: string) {
  if (dimension === 'project') return `/requests?project=${key || 'unassigned'}`
  if (dimension === 'model') return `/requests?model=${encodeURIComponent(key)}`
  if (dimension === 'provider') return `/requests?provider=${encodeURIComponent(key)}`
  if (dimension === 'api_key') return key ? `/requests?api_key=${key}` : null
  if (dimension === 'end_user') return key ? `/requests?end_user=${encodeURIComponent(key)}` : null
  if (dimension.startsWith('tag:') && key) return `/requests?tag=${encodeURIComponent(dimension.slice(4) + ':' + key)}`
  return null
}

export function CostAllocationPage() {
  const [params, setParams] = useSearchParams()
  const range = (RANGES.some((r) => r.value === params.get('range')) ? params.get('range') : '30d') as Range
  const base = params.get('by') ?? 'project'
  const tagKey = params.get('tag') ?? ''
  const set = (patch: Record<string, string>) => setParams((p) => { Object.entries(patch).forEach(([k, v]) => v ? p.set(k, v) : p.delete(k)); return p }, { replace: true })

  const tagsProbe = useResource(() => get<Attribution>(`/api/v1/analytics/attribution?dimension=project&range=${range}`), [range])
  const tagKeys = tagsProbe.data?.tag_keys ?? []
  const effectiveTag = tagKey || tagKeys[0] || ''
  const dimension = base === 'tag' ? (effectiveTag ? `tag:${effectiveTag}` : 'project') : base
  const data = useResource(() => get<Attribution>(`/api/v1/analytics/attribution?dimension=${encodeURIComponent(dimension)}&range=${range}`),
    [dimension, range], 30_000)
  const a = data.data

  return (
    <div className="space-y-8">
      <PageHeader
        title="Cost allocation"
        description="Showback by any dimension. Tag requests with X-AetherGate-Tags: feature=search,env=prod (or OpenAI metadata) and attribute them to your customers' end users with X-AetherGate-User."
        actions={<Segmented label="Time range" value={range} options={RANGES} onChange={(v) => set({ range: v })} />}
      />
      <div className="grid gap-4 sm:grid-cols-3">
        <Tile label={`Spend · ${range}`} value={a ? usd(a.total_cost_usd) : null} sub={a ? `${int(a.total_requests)} requests` : ''} />
        <Tile label="Tag coverage (allocated spend)" value={a ? `${a.allocated_pct.toFixed(1)} %` : null}
          sub={a ? `${usdSmart(a.unallocated_usd)} carries no cost tag` : ''} />
        <Tile label="Cost tag keys seen" value={a ? String(a.tag_keys.length) : null} sub={a?.tag_keys.slice(0, 5).join(', ') || 'none yet'} />
      </div>

      <Panel pad={false} title={<span className="inline-flex items-center gap-2"><PieChart className="size-4" aria-hidden />Spend by {DIMENSIONS.find((d) => d.value === base)?.label.toLowerCase()}{base === 'tag' && effectiveTag ? ` "${effectiveTag}"` : ''}</span>}
        action={
          <div className="flex items-center gap-2">
            <Segmented label="Group by" value={base as (typeof DIMENSIONS)[number]['value']} options={[...DIMENSIONS]} onChange={(v) => set({ by: v })} />
            {base === 'tag' && tagKeys.length > 0 && (
              <select aria-label="Tag key" className={`${inputClass} h-8 w-36 text-[13px]`} value={effectiveTag} onChange={(e) => set({ tag: e.target.value })}>
                {tagKeys.map((k) => <option key={k} value={k}>{k}</option>)}
              </select>
            )}
          </div>
        }>
        {data.error && <div className="p-5"><ErrorBanner error={data.error} onRetry={data.reload} /></div>}
        {!a ? <div className="p-5 space-y-2">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-9" />)}</div>
          : base === 'tag' && !tagKeys.length ? <EmptyState title="No cost tags yet" hint="Send X-AetherGate-Tags: feature=search,env=prod with your requests, or set tags in the Playground." />
          : !a.rows.length ? <EmptyState title="No traffic in this range" />
          : (
            <div className="overflow-x-auto">
              <table className="w-full text-[13px]">
                <thead><tr className="text-left text-[12px] text-ink-3 border-b border-line">
                  <th className="font-normal px-5 h-9">{DIMENSIONS.find((d) => d.value === base)?.label}</th>
                  <th className="font-normal px-3 w-[28%]">Share of spend</th>
                  <th className="font-normal px-3 text-right">Spend</th><th className="font-normal px-3 text-right">Requests</th>
                  <th className="font-normal px-3 text-right">Tokens in / out</th><th className="font-normal px-3 text-right">Cache</th>
                  <th className="font-normal px-3 text-right">Avg latency</th><th className="font-normal px-5 text-right">Saved</th>
                </tr></thead>
                <tbody>
                  {a.rows.map((r) => {
                    const link = explorerLink(a.dimension, r.key)
                    return (
                      <tr key={r.key + r.label} className="border-b border-line last:border-0">
                        <td className="px-5 py-2.5 max-w-64 truncate">
                          {link ? <Link to={link} className="hover:underline underline-offset-4">{r.label}</Link> : <span className="text-ink-3">{r.label}</span>}
                          {r.errors > 0 && <span className="ml-2"><Badge tone="bad">{r.errors} err</Badge></span>}
                        </td>
                        <td className="px-3">
                          <div className="flex items-center gap-2">
                            <div className="h-1.5 flex-1 rounded-full bg-panel-2 overflow-hidden" aria-hidden>
                              <div className="h-full bg-[var(--series-1)]" style={{ width: `${Math.max(1, r.share * 100)}%` }} />
                            </div>
                            <span className="w-12 text-right num text-ink-2">{percent(r.share)}</span>
                          </div>
                        </td>
                        <td className="px-3 text-right font-mono num">{usdSmart(r.cost_usd)}</td>
                        <td className="px-3 text-right num">{int(r.requests)}</td>
                        <td className="px-3 text-right num text-ink-2">{tokens(r.prompt_tokens)} / {tokens(r.completion_tokens)}</td>
                        <td className="px-3 text-right num text-ink-2">{percent(r.cache_hit_ratio, 0)}</td>
                        <td className="px-3 text-right num text-ink-2">{ms(r.avg_latency_ms)}</td>
                        <td className="px-5 text-right font-mono num text-good">{Number(r.saved_usd) > 0 ? usdSmart(r.saved_usd) : '—'}</td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
      </Panel>

      <EndUsersPanel range={range} />
      <FocusExportPanel />
    </div>
  )
}

function Tile({ label, value, sub }: { label: string; value: string | null; sub: string }) {
  return (
    <div className="rounded-lg border border-line bg-panel p-4">
      <p className="text-[12px] text-ink-3">{label}</p>
      {value == null ? <Skeleton className="h-7 w-24 mt-1.5" /> : <p className="mt-1 text-[22px] font-semibold tracking-tight num">{value}</p>}
      <p className="mt-1 text-[12px] text-ink-3 truncate">{sub}</p>
    </div>
  )
}

function EndUsersPanel({ range }: { range: Range }) {
  const res = useResource(() => get<EndUsers>(`/api/v1/analytics/end-users?range=${range}`), [range], 30_000)
  const d = res.data
  return (
    <Panel pad={false} title={<span className="inline-flex items-center gap-2"><Users className="size-4" aria-hidden />End users</span>}
      action={d && <span className="text-[12px] text-ink-3">Daily cap {d.daily_cap_usd ? usdSmart(d.daily_cap_usd) : 'off'} · {int(d.anonymous_requests)} anonymous requests ({usdSmart(d.anonymous_cost_usd)})</span>}>
      {res.error && <div className="p-5"><ErrorBanner error={res.error} onRetry={res.reload} /></div>}
      {!d ? <div className="p-5"><Skeleton className="h-24" /></div>
        : !d.users.length ? <EmptyState title="No end users yet" hint="Send X-AetherGate-User (or the OpenAI 'user' field) to see spend per customer and enforce a daily cap." />
        : (
          <div className="overflow-x-auto">
            <table className="w-full text-[13px]">
              <thead><tr className="text-left text-[12px] text-ink-3 border-b border-line">
                <th className="font-normal px-5 h-9">End user</th><th className="font-normal px-3 text-right">Spend</th>
                <th className="font-normal px-3 text-right">Requests</th><th className="font-normal px-3 text-right">Sessions</th>
                <th className="font-normal px-3 w-56">Today vs daily cap</th><th className="font-normal px-5 text-right">Last seen</th>
              </tr></thead>
              <tbody>
                {d.users.map((u) => (
                  <tr key={u.end_user_id} className="border-b border-line last:border-0">
                    <td className="px-5 py-2.5 font-mono text-[12px]"><Link to={`/requests?end_user=${encodeURIComponent(u.end_user_id)}`} className="hover:underline underline-offset-4">{u.end_user_id}</Link></td>
                    <td className="px-3 text-right font-mono num">{usdSmart(u.cost_usd)}</td>
                    <td className="px-3 text-right num">{int(u.requests)}</td>
                    <td className="px-3 text-right num text-ink-2">{int(u.sessions)}</td>
                    <td className="px-3">
                      {u.cap_used_pct == null ? <span className="text-ink-3 num">{usdSmart(u.today_usd)} today</span> : (
                        <div className="flex items-center gap-2">
                          <div className="h-1.5 flex-1 rounded-full bg-panel-2 overflow-hidden" role="meter" aria-valuenow={Math.min(100, u.cap_used_pct)}
                            aria-valuemin={0} aria-valuemax={100} aria-label={`${u.end_user_id} daily cap used`}>
                            <div className={`h-full ${u.cap_used_pct >= 100 ? 'bg-bad' : u.cap_used_pct >= 80 ? 'bg-warn' : 'bg-accent'}`} style={{ width: `${Math.min(100, u.cap_used_pct)}%` }} />
                          </div>
                          <span className="w-12 text-right num text-ink-2">{u.cap_used_pct.toFixed(0)} %</span>
                          {u.blocked_today > 0 && <Badge tone="bad">{u.blocked_today} blocked</Badge>}
                        </div>
                      )}
                    </td>
                    <td className="px-5 text-right text-ink-3">{relative(u.last_seen_at)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
    </Panel>
  )
}

function lastMonths(n: number) {
  const out: string[] = []
  const d = new Date()
  for (let i = 0; i < n; i++) {
    const m = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() - i, 1))
    out.push(m.toISOString().slice(0, 7))
  }
  return out
}

function FocusExportPanel() {
  const months = lastMonths(6)
  const [month, setMonth] = useState(months[0])
  const [result, setResult] = useState<{ rows: string | null; billed: string | null } | null>(null)
  const run = useAsyncAction(async () => setResult(await download(`/api/v1/analytics/export/focus?month=${month}`, `aethergate-focus-${month}.csv`)))
  return (
    <Panel title={<span className="inline-flex items-center gap-2"><Download className="size-4" aria-hidden />FOCUS 1.2 export</span>}>
      <div className="flex flex-col sm:flex-row sm:items-end gap-4">
        <p className="flex-1 text-[13px] text-ink-3">
          Download the month in the FinOps Foundation's <span className="text-ink-2">FOCUS</span> format (BilledCost, EffectiveCost, ListCost,
          ConsumedQuantity in tokens, SubAccount = project, Tags as JSON, <span className="font-mono">x_</span> columns for tokens and cache savings), ready for
          the same FinOps tooling as your cloud bill. BilledCost sums exactly to the ledger's settled spend.
        </p>
        <div className="flex items-center gap-2">
          <select aria-label="Month" className={`${inputClass} h-9 w-32`} value={month} onChange={(e) => setMonth(e.target.value)}>
            {months.map((m) => <option key={m} value={m}>{m}</option>)}
          </select>
          <Button variant="primary" icon={<Download className="size-3.5" />} loading={run.pending} onClick={() => void run.run()}>Download CSV</Button>
        </div>
      </div>
      {run.error && <div className="mt-3"><ErrorBanner error={run.error} compact /></div>}
      {result && !run.pending && <p className="mt-3 text-[12px] text-good num" role="status">{result.rows} rows · billed {usd(result.billed)}</p>}
    </Panel>
  )
}
