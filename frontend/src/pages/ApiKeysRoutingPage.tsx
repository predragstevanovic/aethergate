import { ArrowDown, ArrowUp, Check, Copy, Pencil, Plus, Trash2 } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { Badge, Button, EmptyState, ErrorBanner, Field, Modal, PageHeader, Panel, Skeleton, inputClass } from '../components/ui/primitives'
import { useAsyncAction, useClipboard } from '../hooks/useInteractions'
import { useResource } from '../hooks/useResource'
import { API_BASE, del, get, patch, post, put, type ApiKeyView, type ProjectList, type ProjectView, type ProviderView, type WorkspaceSettings } from '../lib/api'
import { dateTime, int, relative, usd, usd8 } from '../lib/format'
import { useAuth } from '../context/AuthContext'

interface CreatedKey {
  key: ApiKeyView
  raw_key: string
}

export function ApiKeysRoutingPage() {
  return (
    <div className="space-y-8">
      <PageHeader
        title="Workspace, keys & routing"
        description="Keys authenticate the OpenAI-compatible gateway. Only their SHA-256 hash is stored; the raw key is shown once. Routing priority decides which provider serves a request and where it fails over."
      />
      <WorkspacePanel />
      <KeysPanel />
      <RoutingPanel />
      <QuickstartPanel />
    </div>
  )
}

function KeysPanel() {
  const keys = useResource(() => get<ApiKeyView[]>('/api/v1/tenant/api-keys'), [])
  const projectList = useResource(() => get<ProjectList>('/api/v1/tenant/projects'), [])
  const projects = projectList.data?.projects ?? []
  const activeProjects = projects.filter((p) => p.status === 'ACTIVE')
  const move = useAsyncAction(async (k: ApiKeyView, projectId: string) => {
    await patch(`/api/v1/tenant/api-keys/${k.id}`, { project_id: projectId || null })
    keys.reload()
  })
  const [creating, setCreating] = useState(false)
  const [created, setCreated] = useState<CreatedKey | null>(null)
  const [confirmRevoke, setConfirmRevoke] = useState<ApiKeyView | null>(null)
  const revoke = useAsyncAction(async (id: string) => {
    await del(`/api/v1/tenant/api-keys/${id}`)
    setConfirmRevoke(null)
    keys.reload()
  })

  return (
    <Panel
      pad={false}
      title="API keys"
      action={<Button size="sm" variant="primary" icon={<Plus className="size-3.5" />} onClick={() => setCreating(true)}>Generate key</Button>}
    >
      {keys.error && <div className="p-5"><ErrorBanner error={keys.error} onRetry={keys.reload} /></div>}
      {move.error && <div className="p-5 pb-0"><ErrorBanner error={move.error} compact /></div>}
      {keys.loading ? (
        <div className="p-5 space-y-2">{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-9" />)}</div>
      ) : !keys.data?.length ? (
        <EmptyState title="No API keys yet" hint="Generate one to call /v1/chat/completions from your app." />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-[13px]">
            <thead>
              <tr className="text-left text-[12px] text-ink-3 border-b border-line">
                <th className="font-normal px-5 h-9">Name</th>
                <th className="font-normal px-3">Key</th>
                <th className="font-normal px-3">Project</th>
                <th className="font-normal px-3 text-right">Limits</th>
                <th className="font-normal px-3">Created</th>
                <th className="font-normal px-3">Status</th>
                <th className="px-5" aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {keys.data.map((k) => (
                <tr key={k.id} className={`border-b border-line last:border-0 ${k.active ? '' : 'opacity-55'}`}>
                  <td className="px-5 h-11">{k.name}</td>
                  <td className="px-3 font-mono text-ink-2">{k.key_prefix}••••••••</td>
                  <td className="px-3">
                    {k.active ? (
                      <select aria-label={`Project of ${k.name}`} className={`${inputClass} h-8 w-44 text-[13px]`} value={k.project_id ?? ''}
                        disabled={move.pending} onChange={(e) => void move.run(k, e.target.value)}>
                        <option value="">Unassigned</option>
                        {activeProjects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                      </select>
                    ) : (
                      <span className="text-ink-3">{projects.find((p) => p.id === k.project_id)?.name ?? 'Unassigned'}</span>
                    )}
                  </td>
                  <td className="px-3 text-right font-mono num text-ink-2 whitespace-nowrap">{int(k.rate_limit_rpm)} rpm · {int(k.rate_limit_tpm)} tpm</td>
                  <td className="px-3 text-ink-3 whitespace-nowrap" title={dateTime(k.created_at)}>{relative(k.created_at)}</td>
                  <td className="px-3">{k.active ? <Badge tone="good" dot>Active</Badge> : <Badge>Revoked</Badge>}</td>
                  <td className="px-5 text-right">
                    {k.active && k.key_prefix === 'ag_live_demo' ? (
                      <span className="text-[12px] text-ink-3">shared demo key</span>
                    ) : k.active && (
                      <Button size="sm" variant="ghost" aria-label={`Revoke ${k.name}`} icon={<Trash2 className="size-3.5" />} onClick={() => setConfirmRevoke(k)}>
                        Revoke
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <CreateKeyModal projects={activeProjects} open={creating} onClose={() => setCreating(false)} onCreated={(c) => { setCreating(false); setCreated(c); keys.reload() }} />
      <RevealKeyModal created={created} onClose={() => setCreated(null)} />
      <Modal
        open={confirmRevoke !== null}
        title="Revoke API key"
        onClose={() => setConfirmRevoke(null)}
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirmRevoke(null)}>Cancel</Button>
            <Button variant="danger" loading={revoke.pending} onClick={() => confirmRevoke && void revoke.run(confirmRevoke.id)}>Revoke key</Button>
          </>
        }
      >
        <p className="text-[13px] text-ink-2">
          Requests using <span className="font-mono text-ink">{confirmRevoke?.key_prefix}…</span> ({confirmRevoke?.name}) will be rejected with 401 immediately. This cannot be undone.
        </p>
        {revoke.error && <div className="mt-3"><ErrorBanner error={revoke.error} compact /></div>}
      </Modal>
    </Panel>
  )
}

function CreateKeyModal({ open, onClose, onCreated, projects }: {
  open: boolean
  onClose: () => void
  onCreated: (c: CreatedKey) => void
  projects: ProjectView[]
}) {
  const [name, setName] = useState('')
  const [projectId, setProjectId] = useState('')
  const [rpm, setRpm] = useState('60')
  const [tpm, setTpm] = useState('100000')
  const [touched, setTouched] = useState(false)
  const create = useAsyncAction(async () => {
    const c = await post<CreatedKey>('/api/v1/tenant/api-keys', {
      name: name.trim(), rate_limit_rpm: Number(rpm), rate_limit_tpm: Number(tpm), project_id: projectId || null,
    })
    setName('')
    setTouched(false)
    onCreated(c)
  })
  useEffect(() => {
    if (!open) create.clearError()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])
  const nameError = touched && !name.trim() ? 'Give the key a name' : null
  const rpmError = touched && !(Number(rpm) >= 1 && Number(rpm) <= 10000) ? '1 – 10,000' : null
  const tpmError = touched && !(Number(tpm) >= 100 && Number(tpm) <= 10_000_000) ? '100 – 10,000,000' : null
  const submit = (e: FormEvent) => {
    e.preventDefault()
    setTouched(true)
    if (!name.trim() || rpmError || tpmError || !(Number(rpm) >= 1) || !(Number(tpm) >= 100)) return
    void create.run()
  }
  return (
    <Modal open={open} title="Generate API key" onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" type="submit" form="create-key" loading={create.pending}>Generate</Button></>}>
      <form id="create-key" onSubmit={submit} className="space-y-4" noValidate>
        <Field label="Name" htmlFor="key-name" error={nameError} hint="e.g. Production backend">
          <input id="key-name" className={inputClass} value={name} maxLength={100} aria-invalid={Boolean(nameError)} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Project" htmlFor="key-project" hint="Spend is charged to this project and limited by its monthly budget">
          <select id="key-project" className={inputClass} value={projectId} onChange={(e) => setProjectId(e.target.value)}>
            <option value="">Unassigned</option>
            {projects.map((p) => <option key={p.id} value={p.id}>{p.name}{p.monthly_budget_usd ? ` · ${usd(p.monthly_budget_usd)}/mo` : ''}</option>)}
          </select>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Requests / min" htmlFor="key-rpm" error={rpmError}>
            <input id="key-rpm" inputMode="numeric" className={`${inputClass} font-mono num`} value={rpm} aria-invalid={Boolean(rpmError)} onChange={(e) => setRpm(e.target.value.replace(/\D/g, ''))} />
          </Field>
          <Field label="Tokens / min" htmlFor="key-tpm" error={tpmError}>
            <input id="key-tpm" inputMode="numeric" className={`${inputClass} font-mono num`} value={tpm} aria-invalid={Boolean(tpmError)} onChange={(e) => setTpm(e.target.value.replace(/\D/g, ''))} />
          </Field>
        </div>
        {create.error && <ErrorBanner error={create.error} compact />}
      </form>
    </Modal>
  )
}

function RevealKeyModal({ created, onClose }: { created: CreatedKey | null; onClose: () => void }) {
  const { copied, copy } = useClipboard()
  return (
    <Modal open={created !== null} title="Copy your new key" onClose={onClose} footer={<Button variant="primary" onClick={onClose}>I've stored it safely</Button>}>
      <p className="text-[13px] text-ink-2">This is the only time the full key is shown. AetherGate stores just its SHA-256 hash.</p>
      <div className="mt-4 flex items-center gap-2 rounded-md border border-line bg-panel-2 p-2 pl-3">
        <code className="flex-1 font-mono text-[12px] break-all select-all">{created?.raw_key}</code>
        <Button size="sm" variant="secondary" aria-label="Copy API key" onClick={() => created && void copy(created.raw_key)}
          icon={copied ? <Check className="size-3.5 text-good" /> : <Copy className="size-3.5" />}>
          {copied ? 'Copied' : 'Copy'}
        </Button>
      </div>
    </Modal>
  )
}

function RoutingPanel() {
  const providers = useResource(() => get<ProviderView[]>('/api/v1/tenant/providers'), [])
  const [order, setOrder] = useState<ProviderView[]>([])
  useEffect(() => {
    if (providers.data) setOrder(providers.data)
  }, [providers.data])
  const dirty = providers.data != null && order.map((p) => p.id).join() !== providers.data.map((p) => p.id).join()
  const save = useAsyncAction(async () => {
    await put('/api/v1/tenant/providers/order', { provider_ids: order.map((p) => p.id) })
    providers.reload()
  })
  const toggle = useAsyncAction(async (p: ProviderView) => {
    await patch(`/api/v1/tenant/providers/${p.id}`, { enabled: !p.enabled })
    providers.reload()
  })
  const [modelForm, setModelForm] = useState<ProviderView | 'new' | null>(null)
  const [removing, setRemoving] = useState<ProviderView | null>(null)
  const remove = useAsyncAction(async (p: ProviderView) => {
    await del(`/api/v1/tenant/providers/${p.id}`)
    setRemoving(null)
    providers.reload()
  })
  const move = (i: number, dir: -1 | 1) => {
    const next = [...order]
    ;[next[i], next[i + dir]] = [next[i + dir], next[i]]
    setOrder(next)
  }

  return (
    <Panel
      title="LLM provider fallback chain"
      action={dirty ? (
        <div className="flex gap-2">
          <Button size="sm" variant="ghost" onClick={() => providers.data && setOrder(providers.data)}>Reset</Button>
          <Button size="sm" variant="primary" loading={save.pending} onClick={() => void save.run()}>Save order</Button>
        </div>
      ) : (
        <Button size="sm" variant="secondary" icon={<Plus className="size-3.5" />} onClick={() => setModelForm('new')}
          disabled={order.length >= 6}>Add model</Button>
      )}
    >
      {(providers.error ?? save.error ?? toggle.error) && (
        <div className="mb-4"><ErrorBanner error={(providers.error ?? save.error ?? toggle.error)!} compact /></div>
      )}
      {providers.loading ? (
        <div className="space-y-2">{Array.from({ length: 2 }, (_, i) => <Skeleton key={i} className="h-14" />)}</div>
      ) : (
        <ol className="space-y-2">
          {order.map((p, i) => (
            <li key={p.id} className="flex items-center gap-4 rounded-md border border-line bg-panel-2 px-4 h-16">
              <span className="font-mono text-[12px] text-ink-3 w-20 shrink-0 whitespace-nowrap">Priority {i + 1}</span>
              <div className="min-w-0 flex-1">
                <p className="text-[13px] font-medium flex items-center gap-2">
                  {p.provider}
                  <span className="font-mono font-normal text-ink-2">{p.model_name}</span>
                  {i === 0 && p.enabled && <Badge tone="accent">primary</Badge>}
                </p>
                <p className="text-[12px] text-ink-3 font-mono num">
                  {usd(p.input_cost_per1m)} in · {usd(p.output_cost_per1m)} out per 1M tokens · {p.live ? 'live API' : 'simulator'}
                </p>
              </div>
              <label className="inline-flex items-center gap-2 text-[12px] text-ink-3 cursor-pointer">
                <input type="checkbox" className="accent-[var(--accent)] size-3.5" checked={p.enabled}
                  disabled={toggle.pending || dirty} onChange={() => void toggle.run(p)} />
                Enabled
              </label>
              <div className="flex gap-1">
                <Button size="sm" variant="ghost" aria-label={`Move ${p.provider} up`} disabled={i === 0} onClick={() => move(i, -1)} icon={<ArrowUp className="size-3.5" />} />
                <Button size="sm" variant="ghost" aria-label={`Move ${p.provider} down`} disabled={i === order.length - 1} onClick={() => move(i, 1)} icon={<ArrowDown className="size-3.5" />} />
                <Button size="sm" variant="ghost" aria-label={`Edit ${p.model_name}`} disabled={dirty} onClick={() => setModelForm(p)} icon={<Pencil className="size-3.5" />} />
                <Button size="sm" variant="ghost" aria-label={`Remove ${p.model_name}`} disabled={dirty} onClick={() => setRemoving(p)} icon={<Trash2 className="size-3.5" />} />
              </div>
            </li>
          ))}
        </ol>
      )}
      <p className="mt-4 text-[12px] text-ink-3">
        Pre-flight holds are priced at the most expensive enabled provider, so a failover can never exceed the reserved budget.
        A price change applies from the next request; holds already taken keep their price.
      </p>
      <ModelModal model={modelForm} onClose={() => setModelForm(null)} onSaved={() => { setModelForm(null); providers.reload() }} />
      <Modal open={removing !== null} title={`Remove ${removing?.model_name ?? ''}`} onClose={() => setRemoving(null)}
        footer={<><Button variant="ghost" onClick={() => setRemoving(null)}>Cancel</Button><Button variant="danger" loading={remove.pending} onClick={() => removing && void remove.run(removing)}>Remove model</Button></>}>
        <p className="text-[13px] text-ink-2">The fallback chain closes the gap (priorities are renumbered). The last enabled model can never be removed.</p>
        {remove.error && <div className="mt-3"><ErrorBanner error={remove.error} compact /></div>}
      </Modal>
    </Panel>
  )
}

function ModelModal({ model, onClose, onSaved }: { model: ProviderView | 'new' | null; onClose: () => void; onSaved: () => void }) {
  const editing = model !== null && model !== 'new' ? model : null
  const [provider, setProvider] = useState('GEMINI')
  const [name, setName] = useState('')
  const [input, setInput] = useState('')
  const [output, setOutput] = useState('')
  const [touched, setTouched] = useState(false)
  const save = useAsyncAction(async () => {
    const body = { provider, model_name: name.trim(), input_cost_per1m: input, output_cost_per1m: output }
    if (editing) await put(`/api/v1/tenant/providers/${editing.id}`, body)
    else await post('/api/v1/tenant/providers', body)
    onSaved()
  })
  useEffect(() => {
    if (model === null) return
    setProvider(editing?.provider ?? 'GEMINI')
    setName(editing?.model_name ?? '')
    setInput(editing ? String(Number(editing.input_cost_per1m)) : '')
    setOutput(editing ? String(Number(editing.output_cost_per1m)) : '')
    setTouched(false)
    save.clearError()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [model])
  const nameError = touched && !/^[A-Za-z0-9][A-Za-z0-9._:/-]{1,79}$/.test(name.trim()) ? 'Letters, digits and . _ : / - (2–80)' : null
  const inError = touched && !(Number(input) >= 0 && input.trim() !== '' && Number(input) <= 1000) ? '0 – 1,000' : null
  const outError = touched && !(Number(output) > 0 && Number(output) <= 1000) ? 'More than 0, up to 1,000' : null
  const submit = (e: FormEvent) => {
    e.preventDefault()
    setTouched(true)
    const ok = /^[A-Za-z0-9][A-Za-z0-9._:/-]{1,79}$/.test(name.trim()) && input.trim() !== '' && Number(input) >= 0
      && Number(input) <= 1000 && Number(output) > 0 && Number(output) <= 1000
    if (ok) void save.run()
  }
  return (
    <Modal open={model !== null} title={editing ? `Edit ${editing.model_name}` : 'Add model to the fallback chain'} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" type="submit" form="model-form" loading={save.pending}>{editing ? 'Save' : 'Add model'}</Button></>}>
      <form id="model-form" onSubmit={submit} className="space-y-4" noValidate>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Provider" htmlFor="model-provider" hint={editing ? 'Fixed for an existing model' : 'Without its API key, the simulator serves it'}>
            <select id="model-provider" className={inputClass} value={provider} disabled={Boolean(editing)} onChange={(e) => setProvider(e.target.value)}>
              <option value="OPENAI">OpenAI</option>
              <option value="ANTHROPIC">Anthropic</option>
              <option value="GEMINI">Google Gemini</option>
            </select>
          </Field>
          <Field label="Model" htmlFor="model-name" error={nameError}>
            <input id="model-name" className={`${inputClass} font-mono`} placeholder="gemini-2.5-flash" value={name} aria-invalid={Boolean(nameError)} onChange={(e) => setName(e.target.value)} />
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Input $ / 1M tokens" htmlFor="model-in" error={inError}>
            <input id="model-in" inputMode="decimal" className={`${inputClass} font-mono num`} value={input} aria-invalid={Boolean(inError)} onChange={(e) => setInput(e.target.value.replace(/[^\d.]/g, ''))} />
          </Field>
          <Field label="Output $ / 1M tokens" htmlFor="model-out" error={outError}>
            <input id="model-out" inputMode="decimal" className={`${inputClass} font-mono num`} value={output} aria-invalid={Boolean(outError)} onChange={(e) => setOutput(e.target.value.replace(/[^\d.]/g, ''))} />
          </Field>
        </div>
        <p className="text-[12px] text-ink-3">New models join at the end of the chain; reorder them below. Holds are priced at the most expensive enabled model.</p>
        {save.error && <ErrorBanner error={save.error} compact />}
      </form>
    </Modal>
  )
}

function WorkspacePanel() {
  const settings = useResource(() => get<WorkspaceSettings>('/api/v1/tenant/settings'), [])
  const s = settings.data
  const [saved, setSaved] = useState(false)
  return (
    <Panel title="Workspace">
      {settings.error && <ErrorBanner error={settings.error} onRetry={settings.reload} compact />}
      {/* keyed by the saved values: the form re-initialises from props after every save, no effect needed */}
      {!s ? <Skeleton className="h-24" /> : <WorkspaceForm key={`${s.name}|${s.hard_budget_limit_usd}`} s={s}
        onEdit={() => setSaved(false)} onSaved={() => { setSaved(true); settings.reload() }} />}
      {saved && <p role="status" className="mt-3 text-[12px] text-good">Saved. The new limit applies to the very next request.</p>}
    </Panel>
  )
}

function WorkspaceForm({ s, onSaved, onEdit }: { s: WorkspaceSettings; onSaved: () => void; onEdit: () => void }) {
  const { refreshMe } = useAuth()
  const [name, setName] = useState(s.name)
  const [limit, setLimit] = useState(String(Number(s.hard_budget_limit_usd)))
  const save = useAsyncAction(async () => {
    await put('/api/v1/tenant/settings', { name: name.trim(), hard_budget_limit_usd: limit })
    await refreshMe()
    onSaved()
  })
  const dirty = name.trim() !== s.name || Number(limit) !== Number(s.hard_budget_limit_usd)
  return (
    <>
      <form className="grid gap-4 md:grid-cols-[1fr_220px_auto] md:items-end" onSubmit={(e) => { e.preventDefault(); onEdit(); void save.run() }}>
        <Field label="Name" htmlFor="ws-name" hint={`Slug ${s.slug} · plan ${s.plan_tier}`}>
          <input id="ws-name" className={inputClass} value={name} maxLength={120} disabled={s.demo} onChange={(e) => { setName(e.target.value); onEdit() }} />
        </Field>
        <Field label="Monthly hard limit (USD)" htmlFor="ws-limit" hint={`Committed this month: ${usd8(s.committed_usd)}`}>
          <input id="ws-limit" inputMode="decimal" className={`${inputClass} font-mono num`} value={limit} disabled={s.demo}
            onChange={(e) => { setLimit(e.target.value.replace(/[^\d.]/g, '')); onEdit() }} />
        </Field>
        <Button type="submit" variant="primary" disabled={s.demo || !dirty} loading={save.pending}>Save</Button>
      </form>
      {s.demo && <p className="mt-3 text-[12px] text-ink-3">The shared demo workspace is read-only here. Sign in as Helix or Nimbus to rename a workspace or change its limit.</p>}
      {save.error && <div className="mt-3"><ErrorBanner error={save.error} compact /></div>}
    </>
  )
}

function QuickstartPanel() {
  const { copied, copy } = useClipboard()
  const snippet = `curl ${API_BASE}/v1/chat/completions \\
  -H "Authorization: Bearer ag_live_..." \\
  -H "Content-Type: application/json" \\
  -d '{"model":"auto","stream":true,"messages":[{"role":"user","content":"Hello"}]}'`
  return (
    <Panel title="Quickstart" action={
      <Button size="sm" variant="ghost" onClick={() => void copy(snippet)} icon={copied ? <Check className="size-3.5 text-good" /> : <Copy className="size-3.5" />}>
        {copied ? 'Copied' : 'Copy'}
      </Button>}>
      <p className="text-[13px] text-ink-2 mb-3">Any OpenAI SDK works: set the base URL to <span className="font-mono text-ink">{API_BASE}/v1</span> and use an <span className="font-mono">ag_live_</span> key.</p>
      <pre className="overflow-x-auto rounded-md border border-line bg-panel-2 p-4 font-mono text-[12px] leading-relaxed text-ink-2">{snippet}</pre>
    </Panel>
  )
}
