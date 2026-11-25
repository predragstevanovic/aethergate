import { FilePlus2, GitBranchPlus, Play, Plus, Rocket, Trash2 } from 'lucide-react'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Badge, Button, EmptyState, ErrorBanner, Field, Modal, PageHeader, Panel, Skeleton, inputClass } from '../components/ui/primitives'
import { useAsyncAction } from '../hooks/useInteractions'
import { useResource } from '../hooks/useResource'
import { useRole } from '../hooks/useRole'
import { del, get, post, putIfMatch, type PromptDetail, type PromptTemplate } from '../lib/api'
import { int, ms, percent, relative, usdSmart } from '../lib/format'

const VAR = /\{\{\s*([a-zA-Z_][a-zA-Z0-9_]{0,39})\s*}}/g

function variablesOf(...texts: string[]): string[] {
  const out: string[] = []
  for (const t of texts) for (const m of t.matchAll(VAR)) if (!out.includes(m[1])) out.push(m[1])
  return out
}

export function PromptsPage() {
  const { can, needs } = useRole()
  const list = useResource(() => get<{ templates: PromptTemplate[] }>('/api/v1/tenant/prompts'), [], 20_000)
  const [selected, setSelected] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const templates = list.data?.templates ?? []
  const current = selected ?? templates[0]?.id ?? null

  return (
    <div className="space-y-8">
      <PageHeader
        title="Prompt library"
        description="Versioned prompt templates with {{variables}}. Versions are immutable (a database trigger refuses edits); Developers draft, Admins publish or roll back with one click. Every request records the version it used, so versions can be compared on cost, tokens and latency."
        actions={<Button variant="primary" icon={<Plus className="size-3.5" />} disabled={!can('DEVELOPER')} title={needs('DEVELOPER')} onClick={() => setCreating(true)}>New template</Button>}
      />
      {list.error && <ErrorBanner error={list.error} onRetry={list.reload} />}
      {list.loading && !list.data ? (
        <Skeleton className="h-64" />
      ) : !templates.length ? (
        <Panel><EmptyState title="No prompt templates yet" hint="Move the prompts hard-coded in your app here, then call them by name: { prompt_template: { id: 'support-reply', variables: {…} } }."
          action={can('DEVELOPER') ? <Button variant="primary" onClick={() => setCreating(true)}>Create the first template</Button> : undefined} /></Panel>
      ) : (
        <div className="grid gap-6 lg:grid-cols-[280px_1fr]">
          <Panel pad={false} title="Templates">
            <ul className="divide-y divide-line">
              {templates.map((t) => (
                <li key={t.id}>
                  <button type="button" onClick={() => setSelected(t.id)}
                    className={`w-full text-left px-4 py-3 text-[13px] hover:bg-panel-2 ${t.id === current ? 'bg-panel-2' : ''}`}>
                    <span className="block font-medium">{t.name}</span>
                    <span className="block text-[12px] text-ink-3">v{t.active_version} live{t.latest_version > t.active_version ? ` · v${t.latest_version} draft` : ''} · {int(t.requests30d)} req / 30 d</span>
                  </button>
                </li>
              ))}
            </ul>
          </Panel>
          {current && <TemplateDetailView id={current} onChanged={list.reload} onDeleted={() => { setSelected(null); list.reload() }} />}
        </div>
      )}
      <CreateTemplateModal open={creating} onClose={() => setCreating(false)} onCreated={(id) => { setCreating(false); setSelected(id); list.reload() }} />
    </div>
  )
}

function TemplateDetailView({ id, onChanged, onDeleted }: { id: string; onChanged: () => void; onDeleted: () => void }) {
  const { can, needs } = useRole()
  const detail = useResource(() => get<PromptDetail>(`/api/v1/tenant/prompts/${id}`), [id], 15_000)
  const [drafting, setDrafting] = useState(false)
  const [previewing, setPreviewing] = useState(false)
  const activate = useAsyncAction(async (version: number) => {
    await putIfMatch(`/api/v1/tenant/prompts/${id}/active-version`, detail.data!.template.version, { version })
    detail.reload()
    onChanged()
  })
  const remove = useAsyncAction(async () => {
    await del(`/api/v1/tenant/prompts/${id}`)
    onDeleted()
  })
  if (!detail.data) return detail.error ? <ErrorBanner error={detail.error} onRetry={detail.reload} /> : <Skeleton className="h-64" />
  const t = detail.data.template
  const active = detail.data.versions.find((v) => v.active)
  const error = activate.error ?? remove.error

  return (
    <div className="space-y-6 min-w-0">
      <Panel title={<span>{t.name} <Badge tone="good">v{t.active_version} live</Badge></span>}
        action={<div className="flex gap-1">
          <Button size="sm" variant="secondary" icon={<Play className="size-3.5" />} disabled={!can('DEVELOPER')} title={needs('DEVELOPER')} onClick={() => setPreviewing(true)}>Preview</Button>
          <Button size="sm" variant="secondary" icon={<GitBranchPlus className="size-3.5" />} disabled={!can('DEVELOPER')} title={needs('DEVELOPER')} onClick={() => setDrafting(true)}>New version</Button>
          <Button size="sm" variant="ghost" aria-label="Delete template" icon={<Trash2 className="size-3.5" />} disabled={!can('ADMIN')} title={needs('ADMIN')} loading={remove.pending} onClick={() => void remove.run()} />
        </div>}>
        {t.description && <p className="text-[13px] text-ink-2 mb-3">{t.description}</p>}
        <p className="text-[12px] text-ink-3 mb-3">
          Call it from any OpenAI SDK request body: <code className="font-mono">"prompt_template": {'{'} "id": "{t.name}", "variables": {'{'} {t.variables.map((v) => `"${v}": "…"`).join(', ')} {'}'} {'}'}</code>
        </p>
        {error && <div className="mb-3"><ErrorBanner error={error} compact onRetry={detail.reload} /></div>}
        {active && <TemplateText system={active.system_prompt} user={active.user_template} />}
      </Panel>

      <Panel pad={false} title="Versions" action={<span className="text-[12px] text-ink-3">All-time stats from the request log</span>}>
        <div className="overflow-x-auto">
          <table className="w-full text-[13px]">
            <thead>
              <tr className="text-left text-[12px] text-ink-3 border-b border-line">
                <th className="font-normal px-5 h-9">Version</th>
                <th className="font-normal px-3 text-right">Requests</th>
                <th className="font-normal px-3 text-right">Avg cost</th>
                <th className="font-normal px-3 text-right">Avg tokens in / out</th>
                <th className="font-normal px-3 text-right">Avg latency</th>
                <th className="font-normal px-3 text-right">Cache hits</th>
                <th className="px-5" aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {detail.data.versions.map((v) => (
                <tr key={v.version} className="border-b border-line last:border-0 align-top">
                  <td className="px-5 py-3">
                    <span className="font-medium">v{v.version}</span> {v.active && <Badge tone="good" dot>Live</Badge>}
                    <span className="block text-[12px] text-ink-3">{v.note ?? '—'} · {v.created_by ?? 'seed'} · {relative(v.created_at)}</span>
                  </td>
                  <td className="px-3 py-3 text-right font-mono num">{int(v.requests)}</td>
                  <td className="px-3 py-3 text-right font-mono num">{v.requests ? usdSmart(v.avg_cost_usd) : '—'}</td>
                  <td className="px-3 py-3 text-right font-mono num">{v.requests ? `${Math.round(v.avg_prompt_tokens)} / ${Math.round(v.avg_completion_tokens)}` : '—'}</td>
                  <td className="px-3 py-3 text-right font-mono num">{v.requests ? ms(Math.round(v.avg_latency_ms)) : '—'}</td>
                  <td className="px-3 py-3 text-right font-mono num">{v.requests ? percent(v.cache_hit_rate, 0) : '—'}</td>
                  <td className="px-5 py-3 text-right">
                    {!v.active && (
                      <Button size="sm" variant="ghost" icon={<Rocket className="size-3.5" />} disabled={!can('ADMIN')} title={needs('ADMIN')}
                        loading={activate.pending} onClick={() => void activate.run(v.version)}>
                        {v.version > t.active_version ? 'Publish' : 'Roll back'}
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>

      <VersionModal open={drafting} templateId={id} base={active ? { system: active.system_prompt ?? '', user: active.user_template } : null}
        onClose={() => setDrafting(false)} onSaved={() => { setDrafting(false); detail.reload(); onChanged() }} />
      <PreviewModal open={previewing} templateId={id} variables={t.variables} onClose={() => setPreviewing(false)} />
    </div>
  )
}

function TemplateText({ system, user }: { system: string | null; user: string }) {
  const highlight = (text: string) => text.split(/(\{\{\s*[a-zA-Z_][a-zA-Z0-9_]*\s*}})/g).map((p, i) =>
    p.startsWith('{{') ? <mark key={i} className="bg-accent/15 text-accent rounded px-0.5">{p}</mark> : <span key={i}>{p}</span>)
  return (
    <div className="space-y-2 font-mono text-[12px] whitespace-pre-wrap break-words">
      {system && <p className="rounded-md bg-panel-2 border border-line p-3"><span className="text-ink-3">system · </span>{highlight(system)}</p>}
      <p className="rounded-md bg-panel-2 border border-line p-3"><span className="text-ink-3">user · </span>{highlight(user)}</p>
    </div>
  )
}

function CreateTemplateModal({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (id: string) => void }) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [system, setSystem] = useState('')
  const [user, setUser] = useState('')
  const create = useAsyncAction(async () => {
    const d = await post<PromptDetail>('/api/v1/tenant/prompts', { name: name.trim(), description, system_prompt: system, user_template: user, note: 'initial version' })
    onCreated(d.template.id)
  })
  useEffect(() => {
    if (open) {
      setName('')
      setDescription('')
      setSystem('You are a concise support agent for {{product}}.')
      setUser('Customer {{customer}} asks: {{question}}')
      create.clearError()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])
  const vars = useMemo(() => variablesOf(system, user), [system, user])
  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (name.trim().length >= 2 && user.trim()) void create.run()
  }
  return (
    <Modal wide open={open} title="New prompt template" onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" type="submit" form="prompt-form" icon={<FilePlus2 className="size-3.5" />} loading={create.pending}>Create (v1, live)</Button></>}>
      <form id="prompt-form" onSubmit={submit} className="space-y-4" noValidate>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Name" htmlFor="p-name" hint="Unique; also usable as the id in API calls"><input id="p-name" className={inputClass} value={name} maxLength={80} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label="Description" htmlFor="p-desc"><input id="p-desc" className={inputClass} value={description} maxLength={280} onChange={(e) => setDescription(e.target.value)} /></Field>
        </div>
        <TemplateFields system={system} user={user} setSystem={setSystem} setUser={setUser} vars={vars} />
        {create.error && <ErrorBanner error={create.error} compact />}
      </form>
    </Modal>
  )
}

function TemplateFields({ system, user, setSystem, setUser, vars }: {
  system: string
  user: string
  setSystem: (s: string) => void
  setUser: (s: string) => void
  vars: string[]
}) {
  return (
    <>
      <Field label="System prompt (optional)" htmlFor="p-system">
        <textarea id="p-system" rows={3} maxLength={4000} className={`${inputClass} h-auto py-2 font-mono text-[12px]`} value={system} onChange={(e) => setSystem(e.target.value)} />
      </Field>
      <Field label="User message" htmlFor="p-user" hint={vars.length ? `Variables: ${vars.join(', ')}` : 'Use {{name}} for values supplied per request'}>
        <textarea id="p-user" rows={4} maxLength={8000} className={`${inputClass} h-auto py-2 font-mono text-[12px]`} value={user} onChange={(e) => setUser(e.target.value)} />
      </Field>
    </>
  )
}

function VersionModal({ open, templateId, base, onClose, onSaved }: {
  open: boolean
  templateId: string
  base: { system: string; user: string } | null
  onClose: () => void
  onSaved: () => void
}) {
  const [system, setSystem] = useState('')
  const [user, setUser] = useState('')
  const [note, setNote] = useState('')
  const save = useAsyncAction(async () => {
    await post(`/api/v1/tenant/prompts/${templateId}/versions`, { system_prompt: system, user_template: user, note })
    onSaved()
  })
  useEffect(() => {
    if (open) {
      setSystem(base?.system ?? '')
      setUser(base?.user ?? '')
      setNote('')
      save.clearError()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])
  const vars = useMemo(() => variablesOf(system, user), [system, user])
  return (
    <Modal wide open={open} title="New version (draft)" onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" loading={save.pending} disabled={!user.trim()} onClick={() => void save.run()}>Save draft</Button></>}>
      <div className="space-y-4">
        <p className="text-[13px] text-ink-2">The live version keeps serving traffic until an Admin publishes this draft.</p>
        <TemplateFields system={system} user={user} setSystem={setSystem} setUser={setUser} vars={vars} />
        <Field label="What changed" htmlFor="p-note"><input id="p-note" className={inputClass} value={note} maxLength={200} onChange={(e) => setNote(e.target.value)} placeholder="e.g. shorter answers" /></Field>
        {save.error && <ErrorBanner error={save.error} compact />}
      </div>
    </Modal>
  )
}

function PreviewModal({ open, templateId, variables, onClose }: { open: boolean; templateId: string; variables: string[]; onClose: () => void }) {
  const [values, setValues] = useState<Record<string, string>>({})
  const [rendered, setRendered] = useState<{ role: string; content: string }[] | null>(null)
  const render = useAsyncAction(async () => {
    const r = await post<{ messages: { role: string; content: string }[] }>(`/api/v1/tenant/prompts/${templateId}/render`, { variables: values })
    setRendered(r.messages)
  })
  useEffect(() => {
    if (open) {
      setValues({})
      setRendered(null)
      render.clearError()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])
  return (
    <Modal wide open={open} title="Preview the live version" onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Close</Button><Button variant="primary" loading={render.pending} onClick={() => void render.run()}>Render</Button></>}>
      <div className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          {variables.map((v) => (
            <Field key={v} label={v} htmlFor={`v-${v}`}>
              <input id={`v-${v}`} className={inputClass} value={values[v] ?? ''} onChange={(e) => setValues({ ...values, [v]: e.target.value })} />
            </Field>
          ))}
        </div>
        {render.error && <ErrorBanner error={render.error} compact />}
        {rendered && (
          <div className="space-y-2 font-mono text-[12px] whitespace-pre-wrap">
            {rendered.map((m, i) => <p key={i} className="rounded-md bg-panel-2 border border-line p-3"><span className="text-ink-3">{m.role} · </span>{m.content}</p>)}
          </div>
        )}
      </div>
    </Modal>
  )
}
