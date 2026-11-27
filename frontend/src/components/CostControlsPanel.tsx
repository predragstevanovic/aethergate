import { Gauge } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { useAsyncAction } from '../hooks/useInteractions'
import { useResource } from '../hooks/useResource'
import { useRole } from '../hooks/useRole'
import { get, put, type ControlEvent, type CostControls } from '../lib/api'
import { relative, usd, usdSmart } from '../lib/format'
import { Badge, Button, ErrorBanner, Field, Panel, Skeleton, inputClass } from './ui/primitives'

export const CAP_LABEL: Record<ControlEvent['kind'], string> = {
  KEY_MONTHLY_BUDGET: 'Key monthly budget',
  END_USER_DAILY_CAP: 'End-user daily cap',
  SESSION_BUDGET: 'Session budget',
  SESSION_STEPS: 'Session step limit',
}

const money = (v: string) => (v.trim() ? v.trim() : null)
const whole = (v: string) => (v.trim() ? Number(v.trim()) : null)
const asInput = (v: string | number | null) => (v == null ? '' : String(Number(v)))

/** Workspace spend caps (end user / agent session) and the soft-limit model downgrade. */
export function CostControlsPanel() {
  const { can } = useRole()
  const res = useResource(() => get<{ controls: CostControls; events: ControlEvent[] }>('/api/v1/tenant/cost-controls'), [], 20_000)
  const c = res.data?.controls
  const editable = can('ADMIN') && !!c && !c.demo
  const [userCap, setUserCap] = useState('')
  const [sessionCap, setSessionCap] = useState('')
  const [steps, setSteps] = useState('')
  const [pct, setPct] = useState('')
  const [model, setModel] = useState('')
  const [saved, setSaved] = useState(false)
  useEffect(() => {
    if (!c) return
    setUserCap(asInput(c.end_user_daily_cap_usd))
    setSessionCap(asInput(c.session_cap_usd))
    setSteps(asInput(c.session_max_requests))
    setPct(asInput(c.downgrade_at_pct))
    setModel(c.downgrade_model ?? '')
    // reset only when the server state changes, not while typing
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [c?.end_user_daily_cap_usd, c?.session_cap_usd, c?.session_max_requests, c?.downgrade_at_pct, c?.downgrade_model])
  const save = useAsyncAction(async () => {
    await put('/api/v1/tenant/cost-controls', {
      end_user_daily_cap_usd: money(userCap), session_cap_usd: money(sessionCap), session_max_requests: whole(steps),
      downgrade_at_pct: whole(pct), downgrade_model: pct.trim() ? model || null : null,
    })
    setSaved(true)
    res.reload()
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    setSaved(false)
    void save.run()
  }

  return (
    <Panel pad={false} title={<span className="inline-flex items-center gap-2"><Gauge className="size-4" aria-hidden />Cost controls</span>}
      action={c && (c.downgrade_active
        ? <Badge tone="warn" dot>Downgrade active · {c.used_pct.toFixed(1)} % of limit</Badge>
        : <span className="text-[12px] text-ink-3 num">{usdSmart(c.month_to_date_usd)} of {usd(c.hard_budget_limit_usd)} this month · {c.used_pct.toFixed(1)} %</span>)}>
      {res.error && <div className="p-5 pb-0"><ErrorBanner error={res.error} onRetry={res.reload} /></div>}
      {!c ? (
        <div className="p-5"><Skeleton className="h-24" /></div>
      ) : (
        <form onSubmit={submit} className="p-5 space-y-5">
          <p className="text-[13px] text-ink-3 max-w-3xl">
            Enforced in the metering ledger under the tenant row lock, like the workspace limit, so concurrent requests can never
            overshoot a cap. Send <code className="font-mono">X-AetherGate-User</code> / <code className="font-mono">X-AetherGate-Session</code> (or
            OpenAI <code className="font-mono">user</code> / <code className="font-mono">metadata.session_id</code>) to attribute requests. Empty = off.
          </p>
          <fieldset disabled={!editable} className="grid gap-4 sm:grid-cols-3">
            <Field label="End-user daily cap (USD)" htmlFor="cc-user" hint="Per end user and UTC day → 402 end-user-cap-exceeded">
              <input id="cc-user" inputMode="decimal" className={`${inputClass} font-mono num`} value={userCap} placeholder="off"
                onChange={(e) => setUserCap(e.target.value.replace(/[^\d.]/g, ''))} />
            </Field>
            <Field label="Session budget (USD)" htmlFor="cc-session" hint="Per agent run, lifetime → 402 session-budget-exceeded">
              <input id="cc-session" inputMode="decimal" className={`${inputClass} font-mono num`} value={sessionCap} placeholder="off"
                onChange={(e) => setSessionCap(e.target.value.replace(/[^\d.]/g, ''))} />
            </Field>
            <Field label="Session step limit" htmlFor="cc-steps" hint="Model calls per session → 429 (runaway-agent guard)">
              <input id="cc-steps" inputMode="numeric" className={`${inputClass} font-mono num`} value={steps} placeholder="off"
                onChange={(e) => setSteps(e.target.value.replace(/\D/g, ''))} />
            </Field>
          </fieldset>
          <fieldset disabled={!editable} className="grid gap-4 sm:grid-cols-3 items-end">
            <Field label="Soft limit: downgrade at % of limit" htmlFor="cc-pct" hint="Past it, the cheaper model answers first instead of 402s later">
              <input id="cc-pct" inputMode="numeric" className={`${inputClass} font-mono num`} value={pct} placeholder="off"
                onChange={(e) => setPct(e.target.value.replace(/\D/g, ''))} />
            </Field>
            <Field label="Downgrade to model" htmlFor="cc-model" hint="Cheapest enabled models first">
              <select id="cc-model" className={inputClass} value={model} onChange={(e) => setModel(e.target.value)}>
                <option value="">Choose a model</option>
                {c.downgrade_candidates.map((m) => <option key={m} value={m}>{m}</option>)}
              </select>
            </Field>
            <div className="flex items-center gap-3">
              {editable ? (
                <Button type="submit" variant="primary" loading={save.pending}>Save cost controls</Button>
              ) : (
                <span className="text-[12px] text-ink-3">{c.demo ? 'The shared demo workspace is read-only.' : 'Admins and Owners can change these.'}</span>
              )}
              {saved && !save.pending && !save.error && <span className="text-[12px] text-good" role="status">Saved</span>}
            </div>
          </fieldset>
          {save.error && <ErrorBanner error={save.error} compact />}
        </form>
      )}
      {!!res.data?.events.length && (
        <div className="border-t border-line px-5 py-3 text-[12px] space-y-1.5">
          <p className="font-medium text-ink-2">Blocked by caps</p>
          {res.data.events.slice(0, 6).map((e) => (
            <p key={e.kind + e.scope_key + e.period} className="flex flex-wrap items-center gap-2 text-ink-3">
              <Badge tone="bad">{CAP_LABEL[e.kind]}</Badge>
              <span className="font-mono text-ink-2 truncate max-w-64">{e.scope_key}</span>
              <span className="num">{e.blocked_count} blocked · limit {e.kind === 'SESSION_STEPS' ? Number(e.limit_value) + ' calls' : usdSmart(e.limit_value)}</span>
              <span>last {relative(e.last_at)}</span>
            </p>
          ))}
        </div>
      )}
    </Panel>
  )
}
