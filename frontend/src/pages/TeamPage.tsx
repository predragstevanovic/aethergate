import { Check, Copy, MailPlus, RefreshCw, Trash2, UserMinus } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { Badge, Button, ErrorBanner, Field, Modal, PageHeader, Panel, Skeleton, inputClass } from '../components/ui/primitives'
import { useAuth } from '../context/AuthContext'
import { useAsyncAction, useClipboard } from '../hooks/useInteractions'
import { useResource } from '../hooks/useResource'
import { label, useRole } from '../hooks/useRole'
import { ApiError, del, get, post, request, type AuditEntry, type CreatedInvitation, type MemberRole, type MemberView, type TeamList } from '../lib/api'
import { dateTime, relative } from '../lib/format'

const ROLE_HINT: Record<MemberRole, string> = {
  OWNER: 'Everything, incl. the monthly limit, top-ups and managing Admins',
  ADMIN: 'Projects, guardrails, models, webhooks, alerts; invites Developers and Viewers; publishes prompts',
  DEVELOPER: 'Playground, API keys, prompt drafts; read-only elsewhere',
  VIEWER: 'Read-only console',
}

export function TeamPage() {
  const { can } = useRole()
  const team = useResource(() => get<TeamList>('/api/v1/tenant/members'), [], 20_000)
  const [inviting, setInviting] = useState(false)
  const [invitation, setInvitation] = useState<CreatedInvitation | null>(null)
  const [removing, setRemoving] = useState<MemberView | null>(null)
  const assignable = team.data?.assignable_roles ?? []

  const changeRole = useAsyncAction(async (m: MemberView, role: MemberRole) => {
    await request(`/api/v1/tenant/members/${m.id}`, { method: 'PATCH', headers: { 'If-Match': `"${m.version}"` }, body: JSON.stringify({ role }) })
    team.reload()
  })
  const resend = useAsyncAction(async (m: MemberView) => {
    setInvitation(await post<CreatedInvitation>(`/api/v1/tenant/members/${m.id}/resend`))
    team.reload()
  })

  return (
    <div className="space-y-8">
      <PageHeader
        title="Team"
        description="Invite colleagues with a role. Roles are enforced by the API on every request (not just hidden buttons), membership changes apply on the next request, and every change is written to an append-only audit log."
        actions={<Button variant="primary" icon={<MailPlus className="size-3.5" />} disabled={!assignable.length}
          title={assignable.length ? undefined : 'Requires the Admin role'} onClick={() => setInviting(true)}>Invite member</Button>}
      />

      <Panel pad={false} title="Members" action={team.data && <span className="text-[12px] text-ink-3">You are <strong className="text-ink-2">{label(team.data.your_role)}</strong> · {team.data.members.length} / {team.data.max_members}</span>}>
        {(team.error ?? changeRole.error ?? resend.error) && (
          <div className="p-5"><ErrorBanner error={(team.error ?? changeRole.error ?? resend.error)!} onRetry={team.reload} compact={!team.error} /></div>
        )}
        {team.loading && !team.data ? (
          <div className="p-5 space-y-2">{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-11" />)}</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-[13px]">
              <thead>
                <tr className="text-left text-[12px] text-ink-3 border-b border-line">
                  <th className="font-normal px-5 h-9">Member</th>
                  <th className="font-normal px-3">Role</th>
                  <th className="font-normal px-3">Status</th>
                  <th className="font-normal px-3">Since</th>
                  <th className="px-5" aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {team.data?.members.map((m) => {
                  const manageable = !m.primary_owner && !m.you && m.id && assignable.includes(m.role)
                  return (
                    <tr key={m.id ?? 'owner'} className="border-b border-line last:border-0">
                      <td className="px-5 py-3">
                        <span className="font-medium">{m.email ?? 'Workspace creator'}</span>
                        {m.you && <span className="ml-2 text-[12px] text-ink-3">(you)</span>}
                        {m.primary_owner && <span className="block text-[12px] text-ink-3">Primary owner · cannot be removed</span>}
                      </td>
                      <td className="px-3 py-3">
                        {manageable && m.status === 'ACTIVE' ? (
                          <select aria-label={`Role of ${m.email}`} className={`${inputClass} h-8 w-36 text-[13px]`} value={m.role}
                            disabled={changeRole.pending} onChange={(e) => void changeRole.run(m, e.target.value as MemberRole)}>
                            {assignable.map((r) => <option key={r} value={r}>{label(r)}</option>)}
                          </select>
                        ) : (
                          <RoleBadge role={m.role} />
                        )}
                      </td>
                      <td className="px-3 py-3">
                        {m.status === 'ACTIVE' ? <Badge tone="good" dot>Active</Badge> : (
                          <span className="inline-flex flex-col">
                            <Badge tone="warn" dot>Invited</Badge>
                            {m.invite_expires_at && <span className="text-[11px] text-ink-3 mt-0.5">expires {relative(m.invite_expires_at)}</span>}
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-3 text-ink-3 whitespace-nowrap">{m.joined_at ? relative(m.joined_at) : m.invited_by ? `by ${m.invited_by}` : '—'}</td>
                      <td className="px-5 py-3">
                        <div className="flex justify-end gap-1">
                          {m.status === 'INVITED' && manageable && (
                            <Button size="sm" variant="ghost" icon={<RefreshCw className="size-3.5" />} loading={resend.pending} onClick={() => void resend.run(m)}>New link</Button>
                          )}
                          {(manageable || (m.you && !m.primary_owner)) && (
                            <Button size="sm" variant="ghost" aria-label={m.you ? 'Leave workspace' : `Remove ${m.email}`}
                              icon={m.you ? <UserMinus className="size-3.5" /> : <Trash2 className="size-3.5" />} onClick={() => setRemoving(m)}>
                              {m.you ? 'Leave' : m.status === 'INVITED' ? 'Cancel' : ''}
                            </Button>
                          )}
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      <Panel title="What each role can do">
        <dl className="grid gap-3 sm:grid-cols-2 text-[13px]">
          {(['OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER'] as MemberRole[]).map((r) => (
            <div key={r} className="flex gap-3"><dt className="w-24 shrink-0"><RoleBadge role={r} /></dt><dd className="text-ink-2">{ROLE_HINT[r]}</dd></div>
          ))}
        </dl>
      </Panel>

      {can('ADMIN') && <AuditPanel />}

      <InviteModal open={inviting} roles={assignable} onClose={() => setInviting(false)}
        onInvited={(inv) => { setInviting(false); setInvitation(inv); team.reload() }} />
      <InvitationLinkModal invitation={invitation} onClose={() => setInvitation(null)} />
      <RemoveModal member={removing} onClose={() => setRemoving(null)} onDone={() => { setRemoving(null); team.reload() }} />
    </div>
  )
}

export function RoleBadge({ role }: { role: MemberRole }) {
  const tone = role === 'OWNER' ? 'accent' : role === 'ADMIN' ? 'good' : role === 'DEVELOPER' ? 'warn' : 'neutral'
  return <Badge tone={tone}>{label(role)}</Badge>
}

function InviteModal({ open, roles, onClose, onInvited }: {
  open: boolean
  roles: MemberRole[]
  onClose: () => void
  onInvited: (i: CreatedInvitation) => void
}) {
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<MemberRole>('VIEWER')
  const invite = useAsyncAction(async () => {
    onInvited(await post<CreatedInvitation>('/api/v1/tenant/members', { email: email.trim(), role }))
    setEmail('')
  })
  useEffect(() => {
    if (open) {
      invite.clearError()
      setRole(roles.includes('VIEWER') ? 'VIEWER' : roles[roles.length - 1] ?? 'VIEWER')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])
  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (email.includes('@')) void invite.run()
  }
  return (
    <Modal open={open} title="Invite a member" onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" type="submit" form="invite-form" loading={invite.pending} disabled={!email.includes('@')}>Create invitation</Button></>}>
      <form id="invite-form" onSubmit={submit} className="space-y-4" noValidate>
        <Field label="E-mail" htmlFor="invite-email" hint="Only an account with this address can accept the invitation">
          <input id="invite-email" type="email" className={inputClass} value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="off" />
        </Field>
        <Field label="Role" htmlFor="invite-role" hint={ROLE_HINT[role]}>
          <select id="invite-role" className={inputClass} value={role} onChange={(e) => setRole(e.target.value as MemberRole)}>
            {roles.map((r) => <option key={r} value={r}>{label(r)}</option>)}
          </select>
        </Field>
        {invite.error && <ErrorBanner error={invite.error} compact />}
      </form>
    </Modal>
  )
}

function InvitationLinkModal({ invitation, onClose }: { invitation: CreatedInvitation | null; onClose: () => void }) {
  const { copied, copy } = useClipboard()
  const link = invitation ? `${window.location.origin}/?invite=${encodeURIComponent(invitation.token)}` : ''
  return (
    <Modal open={invitation !== null} title="Share the invitation link" onClose={onClose} footer={<Button variant="primary" onClick={onClose}>Done</Button>}>
      <p className="text-[13px] text-ink-2">
        Send this link to <strong>{invitation?.member.email}</strong>. It works once, for that e-mail address only, and expires in 72 hours.
        It is shown only now: AetherGate stores just its SHA-256 hash.
      </p>
      <div className="mt-4 flex items-center gap-2 rounded-md border border-line bg-panel-2 p-2 pl-3">
        <code className="flex-1 font-mono text-[12px] break-all select-all">{link}</code>
        <Button size="sm" variant="secondary" aria-label="Copy invitation link" onClick={() => void copy(link)}
          icon={copied ? <Check className="size-3.5 text-good" /> : <Copy className="size-3.5" />}>{copied ? 'Copied' : 'Copy'}</Button>
      </div>
    </Modal>
  )
}

function RemoveModal({ member, onClose, onDone }: { member: MemberView | null; onClose: () => void; onDone: () => void }) {
  const { refreshMe, me, switchWorkspace } = useAuth()
  const remove = useAsyncAction(async (m: MemberView) => {
    await del(`/api/v1/tenant/members/${m.id}`)
    if (m.you) {
      const home = me?.workspaces.find((w) => w.id !== me.tenant.id)
      if (home) await switchWorkspace(home.id)
      else await refreshMe()
    }
    onDone()
  })
  const title = member?.you ? 'Leave this workspace' : member?.status === 'INVITED' ? 'Cancel invitation' : `Remove ${member?.email ?? ''}`
  return (
    <Modal open={member !== null} title={title} onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Keep</Button><Button variant="danger" loading={remove.pending} onClick={() => member && void remove.run(member)}>{title}</Button></>}>
      <p className="text-[13px] text-ink-2">
        {member?.status === 'INVITED' ? 'The invitation link stops working immediately.'
          : 'Access ends on their very next request. Keys they created keep working until an Admin revokes them.'}
      </p>
      {remove.error && <div className="mt-3"><ErrorBanner error={remove.error} compact /></div>}
    </Modal>
  )
}

function AuditPanel() {
  const audit = useResource(() => get<{ entries: AuditEntry[] }>('/api/v1/tenant/audit?limit=50'), [], 20_000)
  return (
    <Panel pad={false} title="Audit log" action={<span className="text-[12px] text-ink-3">Append-only: the database rejects UPDATE and DELETE</span>}>
      {audit.error && !(audit.error instanceof ApiError && audit.error.status === 403) && <div className="p-5"><ErrorBanner error={audit.error} onRetry={audit.reload} /></div>}
      {!audit.data?.entries.length ? (
        <p className="px-5 py-6 text-[13px] text-ink-3">{audit.loading ? 'Loading…' : 'No changes recorded yet.'}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-[13px]">
            <thead>
              <tr className="text-left text-[12px] text-ink-3 border-b border-line">
                <th className="font-normal px-5 h-9">When</th>
                <th className="font-normal px-3">Who</th>
                <th className="font-normal px-3">Action</th>
                <th className="font-normal px-5 text-right">Result</th>
              </tr>
            </thead>
            <tbody>
              {audit.data.entries.map((e, i) => (
                <tr key={i} className="border-b border-line last:border-0">
                  <td className="px-5 py-2 text-ink-3 whitespace-nowrap" title={dateTime(e.created_at)}>{relative(e.created_at)}</td>
                  <td className="px-3 py-2">{e.actor_email ?? '—'} {e.actor_role && <span className="text-[12px] text-ink-3">· {label(e.actor_role)}</span>}</td>
                  <td className="px-3 py-2 font-mono text-[12px] text-ink-2">{e.action}</td>
                  <td className="px-5 py-2 text-right">
                    <Badge tone={e.status_code < 300 ? 'good' : e.status_code === 403 ? 'bad' : 'warn'} mono>{e.status_code}</Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  )
}
