import { FlaskConical, Pencil, Plus, ShieldAlert, ShieldCheck, Trash2 } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { Badge, Button, EmptyState, ErrorBanner, Field, Modal, PageHeader, Panel, Segmented, Skeleton, inputClass } from '../components/ui/primitives'
import { useAsyncAction } from '../hooks/useInteractions'
import { useResource } from '../hooks/useResource'
import { del, get, patch, post, putIfMatch, type GuardrailAction, type GuardrailList, type GuardrailTestResult, type GuardrailType, type GuardrailView } from '../lib/api'
import { int, relative } from '../lib/format'

const TYPES: { value: GuardrailType; label: string; hint: string }[] = [
  { value: 'PII_EMAIL', label: 'E-mail addresses', hint: 'Built-in detector, no pattern needed.' },
  { value: 'PII_CARD', label: 'Payment cards', hint: '13–19 digits, Luhn-validated so order numbers do not match.' },
  { value: 'KEYWORDS', label: 'Keywords', hint: 'Comma or newline separated; whole words, case-insensitive.' },
  { value: 'REGEX', label: 'Regular expression', hint: 'Java regex, max 500 chars. Runs under a 25 ms budget; a runaway pattern blocks (fails closed).' },
]
const typeLabel = (t: GuardrailType) => TYPES.find((x) => x.value === t)?.label ?? t

export function GuardrailsPage() {
  const list = useResource(() => get<GuardrailList>('/api/v1/tenant/guardrails'), [], 15_000)
  const [editing, setEditing] = useState<GuardrailView | 'new' | null>(null)
  const [deleting, setDeleting] = useState<GuardrailView | null>(null)
  const toggle = useAsyncAction(async (r: GuardrailView) => {
    await patch(`/api/v1/tenant/guardrails/${r.id}`, { enabled: !r.enabled })
    list.reload()
  })
  const rules = list.data?.rules ?? []

  return (
    <div className="space-y-8">
      <PageHeader
        title="Guardrails"
        description="Prompt policies enforced by the gateway after rate limiting and before the semantic cache, the ledger and any provider. BLOCK rejects with 422 at no cost; REDACT masks the match, so providers, the cache and the logs never see it."
        actions={<Button variant="primary" icon={<Plus className="size-3.5" />} onClick={() => setEditing('new')}
          disabled={list.data != null && rules.length >= list.data.max_rules}>New rule</Button>}
      />

      <TryItPanel onTested={list.reload} />

      <Panel pad={false} title="Rules" action={list.data && <span className="text-[12px] text-ink-3">{rules.filter((r) => r.enabled).length} enabled · {rules.length} / {list.data.max_rules}</span>}>
        {(list.error ?? toggle.error) && <div className="p-5"><ErrorBanner error={(list.error ?? toggle.error)!} onRetry={list.reload} compact={!list.error} /></div>}
        {list.loading && !list.data ? (
          <div className="p-5 space-y-2">{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-12" />)}</div>
        ) : !rules.length ? (
          <EmptyState title="No guardrails yet" hint="Start with a preset: redact e-mail addresses, or block payment card numbers."
            action={<Button variant="primary" onClick={() => setEditing('new')}>Create the first rule</Button>} />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-[13px]">
              <thead>
                <tr className="text-left text-[12px] text-ink-3 border-b border-line">
                  <th className="font-normal px-5 h-9">Rule</th>
                  <th className="font-normal px-3">Detects</th>
                  <th className="font-normal px-3">Action</th>
                  <th className="font-normal px-3 text-right">Hits 30d</th>
                  <th className="font-normal px-3">Enabled</th>
                  <th className="px-5" aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {rules.map((r) => (
                  <tr key={r.id} className={`border-b border-line last:border-0 ${r.enabled ? '' : 'opacity-55'}`}>
                    <td className="px-5 py-3 font-medium">{r.name}</td>
                    <td className="px-3 py-3 max-w-80">
                      <span className="text-ink-2">{typeLabel(r.type)}</span>
                      {r.pattern && <span className="block font-mono text-[12px] text-ink-3 truncate" title={r.pattern}>{r.pattern}</span>}
                    </td>
                    <td className="px-3 py-3"><ActionBadge action={r.action} /></td>
                    <td className="px-3 py-3 text-right font-mono num text-ink-2">
                      {int(r.hits30d)}
                      {r.last_hit_at && <span className="block text-[11px] text-ink-3 font-sans">last {relative(r.last_hit_at)}</span>}
                    </td>
                    <td className="px-3 py-3">
                      <label className="inline-flex items-center gap-2 text-[12px] text-ink-3 cursor-pointer">
                        <input type="checkbox" className="accent-[var(--accent)] size-3.5" checked={r.enabled} disabled={toggle.pending}
                          onChange={() => void toggle.run(r)} aria-label={`${r.enabled ? 'Disable' : 'Enable'} ${r.name}`} />
                        {r.enabled ? 'On' : 'Off'}
                      </label>
                    </td>
                    <td className="px-5 py-3">
                      <div className="flex justify-end gap-1">
                        <Button size="sm" variant="ghost" aria-label={`Edit ${r.name}`} icon={<Pencil className="size-3.5" />} onClick={() => setEditing(r)} />
                        <Button size="sm" variant="ghost" aria-label={`Delete ${r.name}`} icon={<Trash2 className="size-3.5" />} onClick={() => setDeleting(r)} />
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      <Panel pad={false} title="Recent activity">
        {!list.data?.recent_events.length ? (
          <p className="px-5 py-6 text-[13px] text-ink-3">Nothing has fired yet. Try a prompt with an e-mail address in the Playground.</p>
        ) : (
          <ul className="divide-y divide-line">
            {list.data.recent_events.map((e, i) => (
              <li key={i} className="flex items-center gap-4 px-5 h-10 text-[13px]">
                <ActionBadge action={e.action} />
                <span className="flex-1 truncate">{e.rule_name}</span>
                <span className="font-mono text-[12px] text-ink-3 hidden sm:inline">{e.request_id}</span>
                <span className="text-[12px] text-ink-3 num w-16 text-right">{e.matches} match{e.matches === 1 ? '' : 'es'}</span>
                <span className="text-[12px] text-ink-3 w-20 text-right">{relative(e.created_at)}</span>
              </li>
            ))}
          </ul>
        )}
      </Panel>

      <RuleFormModal rule={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); list.reload() }} onStale={list.reload} />
      <DeleteRuleModal rule={deleting} onClose={() => setDeleting(null)} onDone={() => { setDeleting(null); list.reload() }} />
    </div>
  )
}

/** Action label always carries an icon + word, never colour alone. */
function ActionBadge({ action }: { action: GuardrailAction }) {
  return action === 'BLOCK'
    ? <Badge tone="bad"><ShieldAlert className="size-3" aria-hidden />Block</Badge>
    : <Badge tone="warn"><ShieldCheck className="size-3" aria-hidden />Redact</Badge>
}

function TryItPanel({ onTested }: { onTested: () => void }) {
  const [text, setText] = useState('Please email the lab results for MRN-482913 to dr.ana@nimbus-health.example and charge card 4111 1111 1111 1111.')
  const [result, setResult] = useState<GuardrailTestResult | null>(null)
  const test = useAsyncAction(async () => {
    setResult(await post<GuardrailTestResult>('/api/v1/tenant/guardrails/test', { text }))
    onTested()
  })
  return (
    <Panel title="Try it" action={<span className="text-[12px] text-ink-3">Dry run of the enabled rules: nothing is sent or recorded</span>}>
      <div className="grid gap-4 lg:grid-cols-2">
        <div className="space-y-3">
          <label htmlFor="guardrail-sample" className="sr-only">Sample prompt</label>
          <textarea id="guardrail-sample" rows={4} className={`${inputClass} h-auto py-2 resize-none`} value={text} onChange={(e) => setText(e.target.value)} />
          <Button variant="secondary" icon={<FlaskConical className="size-3.5" />} loading={test.pending} disabled={!text.trim()} onClick={() => void test.run()}>Test prompt</Button>
          {test.error && <ErrorBanner error={test.error} compact />}
        </div>
        <div className="rounded-md border border-line bg-panel-2 p-4 text-[13px] min-h-32" aria-live="polite">
          {!result ? (
            <p className="text-ink-3">The result appears here: whether the request would be blocked, or the exact text a provider would receive.</p>
          ) : result.blocked ? (
            <div className="space-y-2">
              <p className="flex items-center gap-2 text-bad font-medium"><ShieldAlert className="size-4" aria-hidden />Blocked by “{result.blocked_by}”</p>
              <p className="text-ink-2">The gateway answers <span className="font-mono">422 guardrail-blocked</span> before any hold: no tokens, no cost.</p>
            </div>
          ) : (
            <div className="space-y-2">
              <p className="flex items-center gap-2 text-good font-medium">
                <ShieldCheck className="size-4" aria-hidden />
                {result.matches.length ? `Allowed with ${result.matches.reduce((s, m) => s + m.count, 0)} redaction(s)` : 'Allowed unchanged'}
              </p>
              <p className="font-mono text-[12px] text-ink-2 whitespace-pre-wrap break-words">
                {result.result_text.split(/(\[REDACTED:[^\]]+\])/g).map((part, i) =>
                  part.startsWith('[REDACTED:') ? <mark key={i} className="bg-warn-soft text-warn rounded px-0.5">{part}</mark> : <span key={i}>{part}</span>)}
              </p>
            </div>
          )}
        </div>
      </div>
    </Panel>
  )
}

function RuleFormModal({ rule, onClose, onSaved, onStale }: {
  rule: GuardrailView | 'new' | null
  onClose: () => void
  onSaved: () => void
  onStale: () => void
}) {
  const editing = rule !== null && rule !== 'new' ? rule : null
  const [name, setName] = useState('')
  const [type, setType] = useState<GuardrailType>('PII_EMAIL')
  const [pattern, setPattern] = useState('')
  const [action, setAction] = useState<GuardrailAction>('REDACT')
  const [touched, setTouched] = useState(false)
  const needsPattern = type === 'KEYWORDS' || type === 'REGEX'

  const save = useAsyncAction(async () => {
    const body = { name: name.trim(), type, action, pattern: needsPattern ? pattern.trim() : null }
    if (editing) await putIfMatch(`/api/v1/tenant/guardrails/${editing.id}`, editing.version, body)
    else await post('/api/v1/tenant/guardrails', body)
    onSaved()
  })

  useEffect(() => {
    if (rule === null) return
    setName(editing?.name ?? '')
    setType(editing?.type ?? 'PII_EMAIL')
    setPattern(editing?.pattern ?? '')
    setAction(editing?.action ?? 'REDACT')
    setTouched(false)
    save.clearError()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rule])

  const nameError = touched && (name.trim().length < 2 || name.trim().length > 80) ? '2 – 80 characters' : null
  const patternError = touched && needsPattern && !pattern.trim() ? (type === 'KEYWORDS' ? 'Give at least one keyword' : 'Give a regular expression') : null
  const submit = (e: FormEvent) => {
    e.preventDefault()
    setTouched(true)
    if (name.trim().length < 2 || name.trim().length > 80 || (needsPattern && !pattern.trim())) return
    void save.run()
  }

  return (
    <Modal open={rule !== null} title={editing ? `Edit ${editing.name}` : 'New guardrail'} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" type="submit" form="guardrail-form" loading={save.pending}>{editing ? 'Save changes' : 'Create rule'}</Button></>}>
      <form id="guardrail-form" onSubmit={submit} className="space-y-4" noValidate>
        <Field label="Name" htmlFor="rule-name" error={nameError} hint="Shown in the 422 error and in the activity log">
          <input id="rule-name" className={inputClass} value={name} maxLength={80} aria-invalid={Boolean(nameError)} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Detects" htmlFor="rule-type" hint={TYPES.find((t) => t.value === type)?.hint}>
          <select id="rule-type" className={inputClass} value={type} onChange={(e) => setType(e.target.value as GuardrailType)}>
            {TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
          </select>
        </Field>
        {needsPattern && (
          <Field label={type === 'KEYWORDS' ? 'Keywords' : 'Pattern'} htmlFor="rule-pattern" error={patternError}>
            <textarea id="rule-pattern" rows={2} maxLength={500} className={`${inputClass} h-auto py-2 font-mono text-[12px] resize-none`}
              placeholder={type === 'KEYWORDS' ? 'Project Atlas, prototype X9' : '(?i)password\\s*[:=]\\s*\\S+'}
              aria-invalid={Boolean(patternError)} value={pattern} onChange={(e) => setPattern(e.target.value)} />
          </Field>
        )}
        <div className="space-y-1.5">
          <span className="block text-[13px] font-medium text-ink-2">Action</span>
          <Segmented label="Action" value={action} onChange={setAction}
            options={[{ value: 'REDACT', label: 'Redact and allow' }, { value: 'BLOCK', label: 'Block (422)' }]} />
        </div>
        {save.error && (
          <div className="space-y-2">
            <ErrorBanner error={save.error} compact />
            {save.error.status === 412 && (
              <button type="button" className="text-[12px] text-accent underline underline-offset-4" onClick={() => { onStale(); onClose() }}>
                Someone else saved this rule; reload the latest version
              </button>
            )}
          </div>
        )}
      </form>
    </Modal>
  )
}

function DeleteRuleModal({ rule, onClose, onDone }: { rule: GuardrailView | null; onClose: () => void; onDone: () => void }) {
  const remove = useAsyncAction(async (r: GuardrailView) => {
    await del(`/api/v1/tenant/guardrails/${r.id}`)
    onDone()
  })
  return (
    <Modal open={rule !== null} title={`Delete ${rule?.name ?? ''}`} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="danger" loading={remove.pending} onClick={() => rule && void remove.run(rule)}>Delete rule</Button></>}>
      <p className="text-[13px] text-ink-2">The rule stops applying on the next request. Its past activity stays in the log under the same name. To pause it instead, switch it off.</p>
      {remove.error && <div className="mt-3"><ErrorBanner error={remove.error} compact /></div>}
    </Modal>
  )
}
