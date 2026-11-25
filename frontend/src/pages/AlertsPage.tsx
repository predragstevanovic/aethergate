import { BellRing, Check, Copy, KeyRound, Plus, RotateCcw, Send, Trash2, Webhook } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { Badge, Button, EmptyState, ErrorBanner, Field, Modal, PageHeader, Panel, Skeleton, inputClass } from '../components/ui/primitives'
import { useAsyncAction, useClipboard } from '../hooks/useInteractions'
import { useResource } from '../hooks/useResource'
import { useRole } from '../hooks/useRole'
import { del, get, patch, post, type AlertList, type EndpointWithSecret, type ProjectList, type WebhookDelivery, type WebhookEndpoint, type WebhookList } from '../lib/api'
import { dateTime, relative, usd, usdSmart } from '../lib/format'

const EVENT_HINT: Record<string, string> = {
  'budget.threshold_crossed': 'A budget alert below crossed its threshold (once per rule per month)',
  'guardrail.blocked': 'A prompt was blocked by a guardrail (the prompt itself is never sent)',
  'api_key.rotated': 'An API key was rotated (old and new prefixes, grace expiry)',
  'member.joined': 'Someone accepted an invitation',
}

export function AlertsPage() {
  const { can } = useRole()
  return (
    <div className="space-y-8">
      <PageHeader
        title="Alerts & webhooks"
        description="Budget alerts fire once per rule and month, decided by a unique index in PostgreSQL. Events are written to a transactional outbox in the same transaction as the change, then delivered as HMAC-signed POSTs with retries."
      />
      <AlertsPanel canEdit={can('ADMIN')} />
      {can('ADMIN') ? <WebhooksPanel /> : (
        <Panel title="Webhooks"><p className="text-[13px] text-ink-3">Webhook endpoints are visible to Admins and Owners.</p></Panel>
      )}
    </div>
  )
}

function AlertsPanel({ canEdit }: { canEdit: boolean }) {
  const alerts = useResource(() => get<AlertList>('/api/v1/tenant/alerts'), [], 15_000)
  const projects = useResource(() => get<ProjectList>('/api/v1/tenant/projects'), [])
  const budgeted = (projects.data?.projects ?? []).filter((p) => p.status === 'ACTIVE' && p.monthly_budget_usd)
  const [scope, setScope] = useState('')
  const [pct, setPct] = useState('80')
  const add = useAsyncAction(async () => {
    await post('/api/v1/tenant/alerts', { project_id: scope || null, threshold_pct: Number(pct) })
    alerts.reload()
  })
  const toggle = useAsyncAction(async (id: string, enabled: boolean) => {
    await patch(`/api/v1/tenant/alerts/${id}`, { enabled })
    alerts.reload()
  })
  const remove = useAsyncAction(async (id: string) => {
    await del(`/api/v1/tenant/alerts/${id}`)
    alerts.reload()
  })
  const error = alerts.error ?? add.error ?? toggle.error ?? remove.error
  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (Number(pct) >= 1 && Number(pct) <= 100) void add.run()
  }

  return (
    <Panel pad={false} title={<span className="inline-flex items-center gap-2"><BellRing className="size-4" aria-hidden />Budget alerts</span>}
      action={alerts.data && <span className="text-[12px] text-ink-3">Period {alerts.data.period} (UTC)</span>}>
      {error && <div className="p-5 pb-0"><ErrorBanner error={error} onRetry={alerts.reload} compact={!alerts.error} /></div>}
      {canEdit && (
        <form onSubmit={submit} className="flex flex-wrap items-end gap-3 p-5 border-b border-line">
          <Field label="Scope" htmlFor="alert-scope">
            <select id="alert-scope" className={`${inputClass} w-56`} value={scope} onChange={(e) => setScope(e.target.value)}>
              <option value="">Workspace (monthly hard limit)</option>
              {budgeted.map((p) => <option key={p.id} value={p.id}>{p.name} · {usd(p.monthly_budget_usd)}/mo</option>)}
            </select>
          </Field>
          <Field label="Threshold %" htmlFor="alert-pct">
            <input id="alert-pct" inputMode="numeric" className={`${inputClass} w-24 font-mono num`} value={pct} onChange={(e) => setPct(e.target.value.replace(/\D/g, ''))} />
          </Field>
          <Button type="submit" variant="primary" icon={<Plus className="size-3.5" />} loading={add.pending}>Add alert</Button>
        </form>
      )}
      {alerts.loading && !alerts.data ? (
        <div className="p-5 space-y-2">{Array.from({ length: 2 }, (_, i) => <Skeleton key={i} className="h-10" />)}</div>
      ) : !alerts.data?.rules.length ? (
        <EmptyState title="No budget alerts" hint="Typical setup: 50 %, 80 % and 100 % of the workspace limit, plus 90 % for each budgeted project." />
      ) : (
        <ul className="divide-y divide-line">
          {alerts.data.rules.map((r) => {
            const used = Math.min(100, r.used_pct)
            return (
              <li key={r.id} className={`flex flex-wrap items-center gap-4 px-5 py-3 text-[13px] ${r.enabled ? '' : 'opacity-55'}`}>
                <span className="w-44 font-medium truncate">{r.scope}</span>
                <span className="w-16 font-mono num">{r.threshold_pct} %</span>
                <div className="flex-1 min-w-48">
                  <div className="h-1.5 rounded-full bg-panel-2 overflow-hidden" role="meter" aria-valuenow={used} aria-valuemin={0} aria-valuemax={100}
                    aria-label={`${r.scope} budget used`}>
                    <div className={`h-full ${r.used_pct >= r.threshold_pct ? 'bg-warn' : 'bg-accent'}`} style={{ width: `${used}%` }} />
                  </div>
                  <span className="text-[12px] text-ink-3">{usdSmart(r.spend_usd)} of {r.limit_usd ? usd(r.limit_usd) : '—'} · {r.used_pct.toFixed(1)} % used</span>
                </div>
                <span className="w-36 text-right">
                  {r.fired_this_month_at ? <Badge tone="warn" dot>Fired {relative(r.fired_this_month_at)}</Badge> : <Badge>Not fired</Badge>}
                </span>
                {canEdit && (
                  <span className="flex gap-1">
                    <Button size="sm" variant="ghost" onClick={() => void toggle.run(r.id, !r.enabled)}>{r.enabled ? 'Pause' : 'Resume'}</Button>
                    <Button size="sm" variant="ghost" aria-label={`Delete ${r.threshold_pct} % alert`} icon={<Trash2 className="size-3.5" />} onClick={() => void remove.run(r.id)} />
                  </span>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {!!alerts.data?.recent.length && (
        <div className="border-t border-line px-5 py-3 text-[12px] text-ink-3 space-y-1">
          <p className="font-medium text-ink-2">Recently fired</p>
          {alerts.data.recent.slice(0, 5).map((f, i) => (
            <p key={i}>{relative(f.fired_at)} · {f.scope} crossed {f.threshold_pct} % ({usdSmart(f.spend_usd)} of {usd(f.limit_usd)})</p>
          ))}
        </div>
      )}
    </Panel>
  )
}

function WebhooksPanel() {
  const hooks = useResource(() => get<WebhookList>('/api/v1/tenant/webhooks'), [], 10_000)
  const [creating, setCreating] = useState(false)
  const [secret, setSecret] = useState<EndpointWithSecret | null>(null)
  const [inspecting, setInspecting] = useState<WebhookEndpoint | null>(null)
  const act = useAsyncAction(async (fn: () => Promise<unknown>) => {
    await fn()
    hooks.reload()
  })
  const endpoints = hooks.data?.endpoints ?? []

  return (
    <Panel pad={false} title={<span className="inline-flex items-center gap-2"><Webhook className="size-4" aria-hidden />Webhook endpoints</span>}
      action={<Button size="sm" variant="primary" icon={<Plus className="size-3.5" />} onClick={() => setCreating(true)}
        disabled={hooks.data != null && endpoints.length >= hooks.data.max_endpoints}>Add endpoint</Button>}>
      {(hooks.error ?? act.error) && <div className="p-5 pb-0"><ErrorBanner error={(hooks.error ?? act.error)!} onRetry={hooks.reload} compact={!hooks.error} /></div>}
      {hooks.loading && !hooks.data ? (
        <div className="p-5"><Skeleton className="h-12" /></div>
      ) : !endpoints.length ? (
        <EmptyState title="No webhook endpoints" hint="Point one at https://webhook.site to watch signed events arrive live." />
      ) : (
        <ul className="divide-y divide-line">
          {endpoints.map((e) => (
            <li key={e.id} className={`px-5 py-4 text-[13px] space-y-2 ${e.enabled ? '' : 'opacity-60'}`}>
              <div className="flex flex-wrap items-center gap-3">
                <span className="font-medium">{e.name}</span>
                <span className="font-mono text-[12px] text-ink-3 truncate max-w-96" title={e.url}>{e.url}</span>
                <span className="ml-auto flex items-center gap-2">
                  {e.last_status && <DeliveryBadge status={e.last_status} />}
                  <span className="text-[12px] text-ink-3">{e.deliveries24h} in 24 h{e.failed24h ? ` · ${e.failed24h} failing` : ''}</span>
                </span>
              </div>
              <div className="flex flex-wrap items-center gap-1.5">
                {e.event_types.map((t) => <Badge key={t} mono>{t}</Badge>)}
                <span className="text-[12px] text-ink-3 font-mono ml-2">secret {e.secret_hint}</span>
              </div>
              <div className="flex flex-wrap gap-1">
                <Button size="sm" variant="secondary" icon={<Send className="size-3.5" />} loading={act.pending}
                  onClick={() => void act.run(async () => { await post(`/api/v1/tenant/webhooks/${e.id}/test`); setInspecting(e) })}>Send test</Button>
                <Button size="sm" variant="ghost" onClick={() => setInspecting(e)}>Deliveries</Button>
                <Button size="sm" variant="ghost" onClick={() => void act.run(() => patch(`/api/v1/tenant/webhooks/${e.id}`, { enabled: !e.enabled }))}>{e.enabled ? 'Disable' : 'Enable'}</Button>
                <Button size="sm" variant="ghost" icon={<KeyRound className="size-3.5" />}
                  onClick={() => void act.run(async () => setSecret(await post<EndpointWithSecret>(`/api/v1/tenant/webhooks/${e.id}/rotate-secret`)))}>Rotate secret</Button>
                <Button size="sm" variant="ghost" aria-label={`Delete ${e.name}`} icon={<Trash2 className="size-3.5" />}
                  onClick={() => void act.run(() => del(`/api/v1/tenant/webhooks/${e.id}`))} />
              </div>
            </li>
          ))}
        </ul>
      )}
      <div className="border-t border-line px-5 py-4 text-[12px] text-ink-3 space-y-1">
        <p className="font-medium text-ink-2">Verifying a delivery (receiver side)</p>
        <p>Header <code className="font-mono">X-AetherGate-Signature: t=&lt;unix&gt;,v1=&lt;hex&gt;</code>. Compute HMAC-SHA256 of <code className="font-mono">t + "." + raw body</code> with the endpoint secret, compare in constant time, reject if <code className="font-mono">t</code> is older than 5 minutes, de-duplicate on <code className="font-mono">X-AetherGate-Event-Id</code>.</p>
      </div>

      <EndpointModal open={creating} eventTypes={hooks.data?.event_types ?? []} onClose={() => setCreating(false)}
        onCreated={(c) => { setCreating(false); setSecret(c); hooks.reload() }} />
      <SecretModal created={secret} onClose={() => setSecret(null)} />
      <DeliveriesModal endpoint={inspecting} onClose={() => { setInspecting(null); hooks.reload() }} />
    </Panel>
  )
}

function DeliveryBadge({ status }: { status: 'PENDING' | 'SUCCEEDED' | 'DEAD' }) {
  return status === 'SUCCEEDED' ? <Badge tone="good" dot>Delivered</Badge> : status === 'DEAD' ? <Badge tone="bad" dot>Failed</Badge> : <Badge tone="warn" dot>Retrying</Badge>
}

function EndpointModal({ open, eventTypes, onClose, onCreated }: {
  open: boolean
  eventTypes: string[]
  onClose: () => void
  onCreated: (c: EndpointWithSecret) => void
}) {
  const [name, setName] = useState('')
  const [url, setUrl] = useState('https://')
  const [events, setEvents] = useState<string[]>([])
  const create = useAsyncAction(async () => {
    onCreated(await post<EndpointWithSecret>('/api/v1/tenant/webhooks', { name: name.trim(), url: url.trim(), event_types: events }))
  })
  useEffect(() => {
    if (open) {
      setName('')
      setUrl('https://')
      setEvents(eventTypes)
      create.clearError()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])
  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (name.trim().length >= 2 && events.length) void create.run()
  }
  return (
    <Modal open={open} title="Add webhook endpoint" onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" type="submit" form="endpoint-form" loading={create.pending}>Create endpoint</Button></>}>
      <form id="endpoint-form" onSubmit={submit} className="space-y-4" noValidate>
        <Field label="Name" htmlFor="hook-name"><input id="hook-name" className={inputClass} value={name} maxLength={80} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="URL" htmlFor="hook-url" hint="HTTPS only. Private, loopback and cloud-metadata addresses are refused (SSRF protection), also after DNS resolution.">
          <input id="hook-url" className={`${inputClass} font-mono text-[12px]`} value={url} maxLength={500} onChange={(e) => setUrl(e.target.value)} />
        </Field>
        <fieldset className="space-y-2">
          <legend className="text-[13px] font-medium text-ink-2 mb-1">Events</legend>
          {eventTypes.map((t) => (
            <label key={t} className="flex items-start gap-2 text-[13px] cursor-pointer">
              <input type="checkbox" className="accent-[var(--accent)] mt-0.5" checked={events.includes(t)}
                onChange={() => setEvents((cur) => cur.includes(t) ? cur.filter((x) => x !== t) : [...cur, t])} />
              <span><span className="font-mono text-[12px]">{t}</span><span className="block text-[12px] text-ink-3">{EVENT_HINT[t]}</span></span>
            </label>
          ))}
        </fieldset>
        {create.error && <ErrorBanner error={create.error} compact />}
      </form>
    </Modal>
  )
}

function SecretModal({ created, onClose }: { created: EndpointWithSecret | null; onClose: () => void }) {
  const { copied, copy } = useClipboard()
  return (
    <Modal open={created !== null} title="Signing secret" onClose={onClose} footer={<Button variant="primary" onClick={onClose}>I've stored it</Button>}>
      <p className="text-[13px] text-ink-2">Use this secret to verify <span className="font-mono">X-AetherGate-Signature</span>. It is shown only now; rotating it invalidates the old one immediately.</p>
      <div className="mt-4 flex items-center gap-2 rounded-md border border-line bg-panel-2 p-2 pl-3">
        <code className="flex-1 font-mono text-[12px] break-all select-all">{created?.secret}</code>
        <Button size="sm" variant="secondary" aria-label="Copy secret" onClick={() => created && void copy(created.secret)}
          icon={copied ? <Check className="size-3.5 text-good" /> : <Copy className="size-3.5" />}>{copied ? 'Copied' : 'Copy'}</Button>
      </div>
    </Modal>
  )
}

function DeliveriesModal({ endpoint, onClose }: { endpoint: WebhookEndpoint | null; onClose: () => void }) {
  const list = useResource(() => endpoint ? get<WebhookDelivery[]>(`/api/v1/tenant/webhooks/${endpoint.id}/deliveries`) : Promise.resolve([]),
    [endpoint?.id], endpoint ? 2_000 : undefined)
  const [open, setOpen] = useState<string | null>(null)
  const redeliver = useAsyncAction(async (id: string) => {
    await post(`/api/v1/tenant/webhooks/deliveries/${id}/redeliver`)
    list.reload()
  })
  return (
    <Modal wide open={endpoint !== null} title={`Deliveries · ${endpoint?.name ?? ''}`} onClose={onClose} footer={<Button onClick={onClose}>Close</Button>}>
      {(list.error ?? redeliver.error) && <ErrorBanner error={(list.error ?? redeliver.error)!} compact />}
      {!list.data?.length ? (
        <p className="text-[13px] text-ink-3">No deliveries yet.</p>
      ) : (
        <ul className="divide-y divide-line text-[13px] max-h-[60vh] overflow-y-auto">
          {list.data.map((d) => (
            <li key={d.id} className="py-2">
              <div className="flex flex-wrap items-center gap-3">
                <DeliveryBadge status={d.status} />
                <span className="font-mono text-[12px]">{d.event_type}</span>
                <span className="text-[12px] text-ink-3" title={dateTime(d.created_at)}>{relative(d.created_at)}</span>
                <span className="text-[12px] text-ink-3">attempt {d.attempts}{d.last_status_code ? ` · HTTP ${d.last_status_code}` : ''}</span>
                {d.next_attempt_at && <span className="text-[12px] text-warn">next try {relative(d.next_attempt_at)}</span>}
                <span className="ml-auto flex gap-1">
                  <Button size="sm" variant="ghost" onClick={() => setOpen(open === d.id ? null : d.id)}>{open === d.id ? 'Hide' : 'Payload'}</Button>
                  {d.status !== 'PENDING' && (
                    <Button size="sm" variant="ghost" icon={<RotateCcw className="size-3.5" />} loading={redeliver.pending} onClick={() => void redeliver.run(d.id)}>Redeliver</Button>
                  )}
                </span>
              </div>
              {d.last_error && <p className="text-[12px] text-bad mt-1">{d.last_error}</p>}
              {open === d.id && (
                <pre className="mt-2 rounded-md bg-panel-2 border border-line p-3 font-mono text-[11px] whitespace-pre-wrap break-all">
                  {JSON.stringify(JSON.parse(d.payload), null, 2)}
                </pre>
              )}
            </li>
          ))}
        </ul>
      )}
    </Modal>
  )
}
