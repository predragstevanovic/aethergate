import { Bot, Search } from 'lucide-react'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { CAP_LABEL } from '../components/CostControlsPanel'
import { Badge, EmptyState, ErrorBanner, Modal, PageHeader, Panel, Segmented, Skeleton, inputClass } from '../components/ui/primitives'
import { useResource } from '../hooks/useResource'
import { get, type Range, type SessionDetail, type SessionRow, type Sessions } from '../lib/api'
import { dateTime, int, ms, relative, tokens, usdSmart } from '../lib/format'

const RANGES: { value: Range; label: string }[] = [
  { value: '24h', label: '24h' }, { value: '7d', label: '7d' }, { value: '30d', label: '30d' },
]

function Meter({ pct, label }: { pct: number | null; label: string }) {
  if (pct == null) return <span className="text-ink-3">—</span>
  const v = Math.min(100, pct)
  return (
    <div className="flex items-center gap-2 min-w-28">
      <div className="h-1.5 flex-1 rounded-full bg-panel-2 overflow-hidden" role="meter" aria-valuenow={v} aria-valuemin={0} aria-valuemax={100} aria-label={label}>
        <div className={`h-full ${pct >= 100 ? 'bg-bad' : pct >= 80 ? 'bg-warn' : 'bg-accent'}`} style={{ width: `${v}%` }} />
      </div>
      <span className="w-10 text-right num text-ink-2 text-[12px]">{pct.toFixed(0)}%</span>
    </div>
  )
}

export function SessionsPage() {
  const [params, setParams] = useSearchParams()
  const range = (RANGES.some((r) => r.value === params.get('range')) ? params.get('range') : '7d') as Range
  const [q, setQ] = useState(params.get('q') ?? '')
  const query = params.get('q') ?? ''
  const res = useResource(() => get<Sessions>(`/api/v1/analytics/sessions?range=${range}${query ? `&q=${encodeURIComponent(query)}` : ''}`),
    [range, query], 15_000)
  const open = params.get('session')
  const d = res.data

  return (
    <div className="space-y-8">
      <PageHeader
        title="Agent sessions"
        description="Group the calls of one agent run with X-AetherGate-Session (or metadata.session_id) to see its cost, steps and models, and stop runaway loops with a per-session budget and step limit."
        actions={<Segmented label="Time range" value={range} options={RANGES} onChange={(v) => setParams((p) => { p.set('range', v); return p }, { replace: true })} />}
      />
      <Panel pad={false} title={<span className="inline-flex items-center gap-2"><Bot className="size-4" aria-hidden />Sessions</span>}
        action={d && <span className="text-[12px] text-ink-3 num">{int(d.total_sessions)} sessions · {usdSmart(d.total_cost_usd)} · budget {d.session_cap_usd ? usdSmart(d.session_cap_usd) : 'off'} · step limit {d.session_max_requests ?? 'off'}</span>}>
        <form className="flex items-center gap-2 p-4 border-b border-line" role="search"
          onSubmit={(e) => { e.preventDefault(); setParams((p) => { if (q.trim()) p.set('q', q.trim()); else p.delete('q'); return p }, { replace: true }) }}>
          <Search className="size-4 text-ink-3" aria-hidden />
          <input aria-label="Search sessions or end users" className={`${inputClass} h-8 max-w-sm`} placeholder="session id or end user" value={q} onChange={(e) => setQ(e.target.value)} />
        </form>
        {res.error && <div className="p-5"><ErrorBanner error={res.error} onRetry={res.reload} /></div>}
        {!d ? <div className="p-5 space-y-2">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-10" />)}</div>
          : !d.sessions.length ? <EmptyState title="No sessions in this range" hint="Set a session id in the Playground, or send X-AetherGate-Session with your agent's calls." />
          : (
            <div className="overflow-x-auto">
              <table className="w-full text-[13px]">
                <thead><tr className="text-left text-[12px] text-ink-3 border-b border-line">
                  <th className="font-normal px-5 h-9">Session</th><th className="font-normal px-3">End user</th>
                  <th className="font-normal px-3 text-right">Steps</th><th className="font-normal px-3 text-right">Cost</th>
                  <th className="font-normal px-3">Budget</th><th className="font-normal px-3">Steps limit</th>
                  <th className="font-normal px-3 text-right">Duration</th><th className="font-normal px-5 text-right">Last activity</th>
                </tr></thead>
                <tbody>
                  {d.sessions.map((s) => <SessionTableRow key={s.session_id} s={s}
                    onOpen={() => setParams((p) => { p.set('session', s.session_id); return p })} />)}
                </tbody>
              </table>
            </div>
          )}
      </Panel>
      <SessionModal id={open} onClose={() => setParams((p) => { p.delete('session'); return p })} />
    </div>
  )
}

function SessionTableRow({ s, onOpen }: { s: SessionRow; onOpen: () => void }) {
  return (
    <tr className="border-b border-line last:border-0 hover:bg-panel-2/50">
      <td className="px-5 py-2.5">
        <button type="button" onClick={onOpen} className="font-mono text-[12px] hover:underline underline-offset-4 text-left">{s.session_id}</button>
        <span className="block text-[11px] text-ink-3">{s.models.join(', ')}</span>
      </td>
      <td className="px-3 font-mono text-[12px] text-ink-2">{s.end_user_id ?? '—'}</td>
      <td className="px-3 text-right num">
        {int(s.steps)}
        {s.blocked > 0 && <span className="ml-2"><Badge tone="bad">{s.blocked} blocked</Badge></span>}
      </td>
      <td className="px-3 text-right font-mono num">{usdSmart(s.cost_usd)}</td>
      <td className="px-3"><Meter pct={s.budget_used_pct} label={`${s.session_id} budget used`} /></td>
      <td className="px-3"><Meter pct={s.steps_used_pct} label={`${s.session_id} steps used`} /></td>
      <td className="px-3 text-right num text-ink-2">{ms(s.duration_ms)}</td>
      <td className="px-5 text-right text-ink-3" title={dateTime(s.last_at)}>{relative(s.last_at)}</td>
    </tr>
  )
}

function SessionModal({ id, onClose }: { id: string | null; onClose: () => void }) {
  const res = useResource(() => id ? get<SessionDetail>(`/api/v1/analytics/sessions/${encodeURIComponent(id)}`) : Promise.resolve(null), [id])
  const d = res.data
  const max = d ? Math.max(...d.steps.map((s) => Number(s.cost_usd)), 0.00000001) : 1
  return (
    <Modal wide open={id !== null} title={`Session · ${id ?? ''}`} onClose={onClose}>
      {res.error && <ErrorBanner error={res.error} compact />}
      {!d ? <Skeleton className="h-40" /> : (
        <div className="space-y-4">
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-[13px]">
            <div><p className="text-[12px] text-ink-3">Cost</p><p className="font-mono num">{usdSmart(d.summary.cost_usd)}</p></div>
            <div><p className="text-[12px] text-ink-3">Steps</p><p className="num">{d.summary.steps}{d.summary.cache_hits ? ` · ${d.summary.cache_hits} cached` : ''}</p></div>
            <div><p className="text-[12px] text-ink-3">Tokens in / out</p><p className="num">{tokens(d.summary.prompt_tokens)} / {tokens(d.summary.completion_tokens)}</p></div>
            <div><p className="text-[12px] text-ink-3">Wall time</p><p className="num">{ms(d.summary.duration_ms)}</p></div>
          </div>
          {d.blocks.map((b) => (
            <div key={b.kind} role="status" className="rounded-md border border-bad/30 bg-bad-soft text-bad px-3 py-2 text-[13px]">
              {CAP_LABEL[b.kind]} stopped this session: {b.blocked_count} further call(s) refused (limit {b.kind === 'SESSION_STEPS' ? `${Number(b.limit_value)} calls` : usdSmart(b.limit_value)}).
            </div>
          ))}
          <ol className="space-y-1 max-h-[50vh] overflow-y-auto pr-1">
            {d.steps.map((s) => (
              <li key={s.request_id} className="grid grid-cols-[2.5rem_1fr_6rem_6rem] items-center gap-3 text-[12px]">
                <span className="text-ink-3 num text-right">#{s.step}</span>
                <div className="min-w-0">
                  <div className="h-2 rounded-sm bg-panel-2 overflow-hidden" aria-hidden>
                    <div className={`h-full ${s.status_code >= 400 ? 'bg-bad' : s.cache_status === 'HIT' ? 'bg-good' : 'bg-[var(--series-1)]'}`}
                      style={{ width: `${Math.max(2, (Number(s.cost_usd) / max) * 100)}%` }} />
                  </div>
                  <span className="text-ink-3 truncate block">
                    +{ms(s.offset_ms)} · {s.model} · {tokens(s.prompt_tokens)}→{tokens(s.completion_tokens)}
                    {s.routing_reason ? ' · downgraded' : ''}{s.failover ? ' · failover' : ''}{s.status_code >= 400 ? ` · HTTP ${s.status_code}` : ''}
                  </span>
                </div>
                <span className="font-mono num text-right">{usdSmart(s.cost_usd)}</span>
                <span className="font-mono num text-right text-ink-3">Σ {usdSmart(s.cumulative_usd)}</span>
              </li>
            ))}
          </ol>
          <Link to={`/requests?session=${encodeURIComponent(d.summary.session_id)}`} className="text-[12px] text-accent hover:underline underline-offset-4">
            Open these requests in the explorer →
          </Link>
        </div>
      )}
    </Modal>
  )
}
