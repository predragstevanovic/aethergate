import { Archive, ArchiveRestore, CircleAlert, OctagonAlert, Pencil, Plus, Trash2 } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { DailySpendChart } from '../components/charts'
import { Badge, Button, EmptyState, ErrorBanner, Field, Modal, PageHeader, Panel, Segmented, Skeleton, inputClass } from '../components/ui/primitives'
import { useAsyncAction } from '../hooks/useInteractions'
import { useResource } from '../hooks/useResource'
import { del, get, post, putIfMatch, type ArchiveResult, type ProjectDetail, type ProjectList, type ProjectView } from '../lib/api'
import { dateTime, int, percent, relative, usd, usd8, usdSmart } from '../lib/format'

type Filter = 'ACTIVE' | 'ARCHIVED' | 'ALL'

export function ProjectsPage() {
  const list = useResource(() => get<ProjectList>('/api/v1/tenant/projects'), [], 15_000)
  const [filter, setFilter] = useState<Filter>('ACTIVE')
  const [editing, setEditing] = useState<ProjectView | 'new' | null>(null)
  const [archiving, setArchiving] = useState<ProjectView | null>(null)
  const [deleting, setDeleting] = useState<ProjectView | null>(null)
  const [detail, setDetail] = useState<ProjectView | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const restore = useAsyncAction(async (p: ProjectView) => {
    await post<ProjectView>(`/api/v1/tenant/projects/${p.id}/restore`)
    setNotice(`"${p.name}" is active again. Its old keys stay revoked; generate new ones in Keys & Routing.`)
    list.reload()
  })

  const projects = list.data?.projects ?? []
  const active = projects.filter((p) => p.status === 'ACTIVE')
  const shown = filter === 'ALL' ? projects : projects.filter((p) => p.status === filter)
  const committed = active.reduce((s, p) => s + Number(p.committed_usd), 0)

  return (
    <div className="space-y-8">
      <PageHeader
        title="Projects"
        description="Cost centres inside this workspace. Every API key belongs to a project, every ledger line is charged to one, and a project's monthly budget is enforced at pre-flight, before a single token is generated."
        actions={
          <Button variant="primary" icon={<Plus className="size-3.5" />} onClick={() => setEditing('new')}
            disabled={list.data != null && active.length >= list.data.max_active_projects}>
            New project
          </Button>
        }
      />

      <section aria-label="Spend summary" className="grid gap-4 sm:grid-cols-3">
        <Tile label="Committed this month" value={list.data ? usd(committed.toFixed(8)) : null}
          hint="Settled spend + holds in flight, active projects" />
        <Tile label="Unassigned this month" value={list.data ? usd(list.data.unassigned.month_to_date_usd) : null}
          hint={list.data ? `${int(list.data.unassigned.requests30d)} requests in 30 days from keys without a project` : ''} />
        <Tile label="Active projects" value={list.data ? `${active.length} / ${list.data.max_active_projects}` : null}
          hint={list.data ? `${projects.length - active.length} archived` : ''} />
      </section>

      {notice && (
        <p role="status" className="rounded-md border border-good/30 bg-good-soft px-4 py-2.5 text-[13px] text-ink-2 flex justify-between gap-4">
          {notice}
          <button type="button" className="text-ink-3 hover:text-ink" onClick={() => setNotice(null)} aria-label="Dismiss">✕</button>
        </p>
      )}

      <Panel
        pad={false}
        title="All projects"
        action={<Segmented label="Filter projects" value={filter} onChange={setFilter}
          options={[{ value: 'ACTIVE', label: 'Active' }, { value: 'ARCHIVED', label: 'Archived' }, { value: 'ALL', label: 'All' }]} />}
      >
        {(list.error ?? restore.error) && (
          <div className="p-5"><ErrorBanner error={(list.error ?? restore.error)!} onRetry={list.reload} compact={!list.error} /></div>
        )}
        {list.loading && !list.data ? (
          <div className="p-5 space-y-2">{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-14" />)}</div>
        ) : !shown.length ? (
          <EmptyState
            title={filter === 'ARCHIVED' ? 'No archived projects' : 'No projects yet'}
            hint="Create a project per product or team, give it a monthly budget, and generate API keys inside it."
            action={filter !== 'ARCHIVED' && <Button variant="primary" onClick={() => setEditing('new')}>Create the first project</Button>}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-[13px]">
              <thead>
                <tr className="text-left text-[12px] text-ink-3 border-b border-line">
                  <th className="font-normal px-5 h-9">Project</th>
                  <th className="font-normal px-3 min-w-56">Monthly budget</th>
                  <th className="font-normal px-3 text-right">Requests 30d</th>
                  <th className="font-normal px-3 text-right">Keys</th>
                  <th className="px-5" aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {shown.map((p) => (
                  <tr key={p.id} className={`border-b border-line last:border-0 ${p.status === 'ARCHIVED' ? 'opacity-60' : ''}`}>
                    <td className="px-5 py-3 max-w-72">
                      <button type="button" className="text-left group" onClick={() => setDetail(p)}>
                        <span className="font-medium group-hover:underline underline-offset-4">{p.name}</span>
                        {p.status === 'ARCHIVED' && <span className="ml-2"><Badge>Archived</Badge></span>}
                        {p.description && <span className="block text-[12px] text-ink-3 truncate">{p.description}</span>}
                      </button>
                    </td>
                    <td className="px-3 py-3"><BudgetBar p={p} /></td>
                    <td className="px-3 py-3 text-right font-mono num text-ink-2 whitespace-nowrap">
                      {int(p.requests30d)}
                      <span className="block text-[11px] text-ink-3">{p.requests30d ? `${percent(p.cache_hits30d / p.requests30d, 0)} cache hits` : '—'}</span>
                    </td>
                    <td className="px-3 py-3 text-right font-mono num text-ink-2">{int(p.active_keys)}</td>
                    <td className="px-5 py-3">
                      <div className="flex justify-end gap-1">
                        {p.status === 'ACTIVE' ? (
                          <>
                            <Button size="sm" variant="ghost" aria-label={`Edit ${p.name}`} icon={<Pencil className="size-3.5" />} onClick={() => setEditing(p)} />
                            <Button size="sm" variant="ghost" aria-label={`Archive ${p.name}`} icon={<Archive className="size-3.5" />} onClick={() => setArchiving(p)} />
                          </>
                        ) : (
                          <Button size="sm" variant="ghost" icon={<ArchiveRestore className="size-3.5" />} loading={restore.pending} onClick={() => void restore.run(p)}>
                            Restore
                          </Button>
                        )}
                        <Button size="sm" variant="ghost" aria-label={`Delete ${p.name}`} icon={<Trash2 className="size-3.5" />} onClick={() => setDeleting(p)} />
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      <ProjectFormModal project={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); list.reload() }} onStale={list.reload} />
      <ArchiveModal project={archiving} onClose={() => setArchiving(null)}
        onDone={(r) => { setArchiving(null); setNotice(`"${r.project.name}" archived. ${r.revoked_keys} key${r.revoked_keys === 1 ? '' : 's'} revoked; its history stays in the ledger.`); list.reload() }} />
      <DeleteModal project={deleting} onClose={() => setDeleting(null)} onDone={() => { setDeleting(null); list.reload() }} />
      <DetailModal project={detail} onClose={() => setDetail(null)} />
    </div>
  )
}

function Tile({ label, value, hint }: { label: string; value: string | null; hint: string }) {
  return (
    <div className="rounded-lg border border-line bg-panel px-5 py-4">
      <p className="text-[12px] text-ink-3">{label}</p>
      {value == null ? <Skeleton className="h-7 w-24 mt-1.5" /> : <p className="mt-1 text-2xl font-semibold num tracking-tight">{value}</p>}
      <p className="mt-1 text-[12px] text-ink-3 truncate">{hint}</p>
    </div>
  )
}

/** Committed vs budget. Status colour always comes with an icon + text, never colour alone. */
function BudgetBar({ p }: { p: ProjectView }) {
  if (p.monthly_budget_usd == null) {
    return (
      <div>
        <p className="num font-mono text-ink-2">{usdSmart(p.month_to_date_usd)} <span className="text-ink-3 font-sans">this month</span></p>
        <p className="text-[11px] text-ink-3">No cap: bounded only by the workspace budget</p>
      </div>
    )
  }
  const u = p.budget_utilization ?? 0
  const level = u >= 1 ? 'bad' : u >= 0.8 ? 'warn' : 'ok'
  const fill = level === 'bad' ? 'var(--bad)' : level === 'warn' ? 'var(--warn)' : 'var(--accent)'
  return (
    <div className="space-y-1.5">
      <div className="flex items-baseline justify-between gap-3">
        <span className="num font-mono text-ink">{usdSmart(p.committed_usd)} <span className="text-ink-3">/ {usd(p.monthly_budget_usd)}</span></span>
        <span className={`text-[11px] inline-flex items-center gap-1 ${level === 'bad' ? 'text-bad' : level === 'warn' ? 'text-warn' : 'text-ink-3'}`}>
          {level === 'bad' && <OctagonAlert className="size-3" aria-hidden />}
          {level === 'warn' && <CircleAlert className="size-3" aria-hidden />}
          {level === 'bad' ? 'At limit' : level === 'warn' ? 'Near limit' : ''} {percent(u, 0)}
        </span>
      </div>
      <div className="h-1.5 rounded-full bg-panel-2 overflow-hidden" role="meter" aria-valuemin={0} aria-valuemax={100}
        aria-valuenow={Math.round(Math.min(u, 1) * 100)} aria-label={`${p.name} budget used`}>
        <div className="h-full rounded-full" style={{ width: `${Math.min(u, 1) * 100}%`, background: fill }} />
      </div>
    </div>
  )
}

function ProjectFormModal({ project, onClose, onSaved, onStale }: {
  project: ProjectView | 'new' | null
  onClose: () => void
  onSaved: () => void
  onStale: () => void
}) {
  const editing = project !== null && project !== 'new' ? project : null
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [budget, setBudget] = useState('')
  const [touched, setTouched] = useState(false)

  const save = useAsyncAction(async () => {
    const body = { name: name.trim(), description: description.trim() || null, monthly_budget_usd: budget.trim() === '' ? null : budget.trim() }
    if (editing) await putIfMatch(`/api/v1/tenant/projects/${editing.id}`, editing.version, body)
    else await post('/api/v1/tenant/projects', body)
    onSaved()
  })

  useEffect(() => {
    if (project === null) return
    setName(editing?.name ?? '')
    setDescription(editing?.description ?? '')
    setBudget(editing?.monthly_budget_usd ? String(Number(editing.monthly_budget_usd)) : '')
    setTouched(false)
    save.clearError()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [project])


  const nameError = touched && (name.trim().length < 2 || name.trim().length > 80) ? '2 – 80 characters' : null
  const budgetValue = Number(budget)
  const budgetError = touched && budget.trim() !== '' && !(budgetValue > 0 && budgetValue <= 1_000_000) ? 'A positive amount up to $1,000,000, or empty for no cap' : null
  const stale = save.error?.status === 412

  const submit = (e: FormEvent) => {
    e.preventDefault()
    setTouched(true)
    if (name.trim().length < 2 || name.trim().length > 80) return
    if (budget.trim() !== '' && !(budgetValue > 0 && budgetValue <= 1_000_000)) return
    void save.run()
  }

  return (
    <Modal open={project !== null} title={editing ? `Edit ${editing.name}` : 'New project'} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" type="submit" form="project-form" loading={save.pending}>{editing ? 'Save changes' : 'Create project'}</Button></>}>
      <form id="project-form" onSubmit={submit} className="space-y-4" noValidate>
        <Field label="Name" htmlFor="project-name" error={nameError} hint="Unique among active projects, e.g. Support Copilot">
          <input id="project-name" className={inputClass} value={name} maxLength={80} aria-invalid={Boolean(nameError)} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Description" htmlFor="project-description" hint="Optional, up to 280 characters">
          <textarea id="project-description" rows={2} maxLength={280} className={`${inputClass} h-auto py-2 resize-none`} value={description} onChange={(e) => setDescription(e.target.value)} />
        </Field>
        <Field label="Monthly budget (USD)" htmlFor="project-budget" error={budgetError}
          hint={editing ? `Committed this month: ${usd8(editing.committed_usd)}. A budget below that is rejected; archive to stop traffic instead.` : 'Empty = no cap. Requests that would cross it get HTTP 402 before any token is generated.'}>
          <input id="project-budget" inputMode="decimal" placeholder="No cap" className={`${inputClass} font-mono num`} value={budget}
            aria-invalid={Boolean(budgetError)} onChange={(e) => setBudget(e.target.value.replace(/[^\d.]/g, ''))} />
        </Field>
        {save.error && (
          <div className="space-y-2">
            <ErrorBanner error={save.error} compact />
            {stale && (
              <p className="text-[12px] text-ink-3">
                Someone saved this project in another tab or session. <button type="button" className="text-accent underline underline-offset-4" onClick={() => { onStale(); onClose() }}>Reload the latest version</button>
              </p>
            )}
          </div>
        )}
      </form>
    </Modal>
  )
}

function ArchiveModal({ project, onClose, onDone }: { project: ProjectView | null; onClose: () => void; onDone: (r: ArchiveResult) => void }) {
  const archive = useAsyncAction(async (p: ProjectView) => onDone(await post<ArchiveResult>(`/api/v1/tenant/projects/${p.id}/archive`)))
  return (
    <Modal open={project !== null} title={`Archive ${project?.name ?? ''}`} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="danger" loading={archive.pending} onClick={() => project && void archive.run(project)}>Archive project</Button></>}>
      <div className="space-y-3 text-[13px] text-ink-2">
        <p>
          {project?.active_keys
            ? <><span className="text-ink font-medium">{project.active_keys} active key{project.active_keys === 1 ? '' : 's'}</span> will be revoked in the same transaction, so its traffic stops at once (401).</>
            : 'It has no active keys.'}
        </p>
        <p>Its spend history stays in the append-only ledger. You can restore the project later, but revoked keys are never reactivated.</p>
        {archive.error && <ErrorBanner error={archive.error} compact />}
      </div>
    </Modal>
  )
}

function DeleteModal({ project, onClose, onDone }: { project: ProjectView | null; onClose: () => void; onDone: () => void }) {
  const remove = useAsyncAction(async (p: ProjectView) => {
    await del(`/api/v1/tenant/projects/${p.id}`)
    onDone()
  })
  useEffect(() => {
    if (project) remove.clearError()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [project])
  return (
    <Modal open={project !== null} title={`Delete ${project?.name ?? ''}`} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="danger" loading={remove.pending} onClick={() => project && void remove.run(project)}>Delete permanently</Button></>}>
      <div className="space-y-3 text-[13px] text-ink-2">
        <p>Deleting is only for projects created by mistake. Keys inside it become unassigned.</p>
        <p>If the project ever handled a request, the ledger references it and the API refuses (409). Archive it instead.</p>
        {remove.error && <ErrorBanner error={remove.error} compact />}
      </div>
    </Modal>
  )
}

function DetailModal({ project, onClose }: { project: ProjectView | null; onClose: () => void }) {
  const detail = useResource<ProjectDetail | null>(
    () => (project ? get<ProjectDetail>(`/api/v1/tenant/projects/${project.id}`) : Promise.resolve(null)), [project?.id])
  const d = detail.data
  return (
    <Modal wide open={project !== null} title={project?.name ?? ''} onClose={onClose}>
      {detail.error && <ErrorBanner error={detail.error} onRetry={detail.reload} compact />}
      {!d ? (
        <div className="space-y-3"><Skeleton className="h-16" /><Skeleton className="h-40" /></div>
      ) : (
        <div className="space-y-5">
          <dl className="grid grid-cols-2 sm:grid-cols-4 gap-4 text-[12px]">
            <Stat label="Month to date" value={usd8(d.project.month_to_date_usd)} />
            <Stat label="Holds in flight" value={usd8(d.project.open_holds_usd)} />
            <Stat label="Remaining" value={d.project.remaining_usd == null ? 'No cap' : usd8(d.project.remaining_usd)} />
            <Stat label="Spend 30 days" value={usd8(d.project.cost30d_usd)} />
          </dl>
          <section aria-label="Daily spend">
            <h3 className="text-[13px] font-medium mb-2">Daily spend, last 30 days</h3>
            {d.daily.length ? <DailySpendChart days={d.daily} /> : <p className="text-[13px] text-ink-3">No traffic in the last 30 days.</p>}
          </section>
          <section aria-label="Keys">
            <h3 className="text-[13px] font-medium mb-2">API keys ({d.keys.length})</h3>
            {d.keys.length ? (
              <ul className="divide-y divide-line rounded-md border border-line">
                {d.keys.map((k) => (
                  <li key={k.id} className="flex items-center justify-between px-3 h-9 text-[13px]">
                    <span>{k.name} <span className="font-mono text-ink-3">{k.key_prefix}••••</span></span>
                    {k.active ? <Badge tone="good" dot>Active</Badge> : <Badge>Revoked</Badge>}
                  </li>
                ))}
              </ul>
            ) : <p className="text-[13px] text-ink-3">No keys. Generate one in Keys &amp; Routing and pick this project.</p>}
          </section>
          <p className="text-[12px] text-ink-3">
            Created {dateTime(d.project.created_at)} · updated {relative(d.project.updated_at)} · version {d.project.version}
            {d.project.archived_at && <> · archived {dateTime(d.project.archived_at)}</>}
          </p>
        </div>
      )}
    </Modal>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border border-line bg-panel-2 px-3 py-2">
      <dt className="text-ink-3">{label}</dt>
      <dd className="mt-0.5 font-mono num text-ink text-[13px]">{value}</dd>
    </div>
  )
}
