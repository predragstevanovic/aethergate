import { BellRing, Bot, FlaskConical, FolderKanban, Gauge, KeyRound, LibraryBig, ListFilter, LogOut, Moon, PieChart, ScrollText, ShieldCheck, Sun, TrendingUp, Users, X } from 'lucide-react'
import { useState } from 'react'
import { label } from '../../hooks/useRole'
import { NavLink, Outlet } from 'react-router'
import { useAuth } from '../../context/AuthContext'
import { useTheme } from '../../hooks/useTheme'
import { Badge } from '../ui/primitives'
import { ErrorBoundary } from './ErrorBoundary'
import { Logo } from './Logo'

const NAV = [
  { to: '/', label: 'Overview', icon: Gauge, end: true },
  { to: '/playground', label: 'Playground', icon: FlaskConical },
  { to: '/ledger', label: 'Ledger', icon: ScrollText },
  { to: '/finops', label: 'FinOps', icon: TrendingUp },
  { to: '/allocation', label: 'Cost allocation', icon: PieChart },
  { to: '/sessions', label: 'Sessions', icon: Bot },
  { to: '/requests', label: 'Requests', icon: ListFilter },
  { to: '/projects', label: 'Projects', icon: FolderKanban },
  { to: '/guardrails', label: 'Guardrails', icon: ShieldCheck },
  { to: '/prompts', label: 'Prompts', icon: LibraryBig },
  { to: '/alerts', label: 'Alerts', icon: BellRing },
  { to: '/team', label: 'Team', icon: Users },
  { to: '/settings', label: 'Settings', icon: KeyRound },
]

export function AppShell() {
  const { me, signOut, switchWorkspace, notice, dismissNotice } = useAuth()
  const [switching, setSwitching] = useState(false)
  const { theme, toggle } = useTheme()
  return (
    <div className="min-h-full flex flex-col md:flex-row">
      <div className="md:w-56 md:shrink-0 md:border-r border-b md:border-b-0 border-line bg-panel">
      <nav aria-label="Primary" className="md:sticky md:top-0 md:h-screen flex md:flex-col">
        <div className="flex items-center gap-2.5 px-5 h-14 md:h-16 shrink-0">
          <Logo className="size-6" />
          <span className="font-semibold tracking-tight">AetherGate</span>
        </div>
        <ul className="flex md:flex-col gap-0.5 px-2 md:px-3 md:py-2 overflow-x-auto items-center md:items-stretch">
          {NAV.map(({ to, label, icon: Icon, end }) => (
            <li key={to}>
              <NavLink
                to={to}
                end={end}
                className={({ isActive }) =>
                  `flex items-center gap-2.5 rounded-md px-2.5 h-8 text-[13px] whitespace-nowrap transition-colors ${
                    isActive ? 'bg-panel-2 text-ink border border-line' : 'text-ink-3 hover:text-ink border border-transparent'
                  }`
                }
              >
                <Icon className="size-4" aria-hidden />
                <span className="hidden sm:inline">{label}</span>
              </NavLink>
            </li>
          ))}
        </ul>
        <div className="hidden md:block mt-auto px-5 py-4 border-t border-line text-[12px] text-ink-3 space-y-1">
          <p>Java 21 · Spring Boot · pgvector</p>
          <a className="hover:text-ink underline-offset-4 hover:underline" href={import.meta.env.VITE_REPO_URL ?? 'https://github.com'} target="_blank" rel="noreferrer">
            Source &amp; architecture ↗
          </a>
        </div>
      </nav>
      </div>

      <div className="flex-1 min-w-0 flex flex-col">
        <header className="sticky top-0 z-20 flex items-center justify-between gap-4 border-b border-line bg-bg/85 backdrop-blur px-5 sm:px-8 h-14">
          <div className="flex items-center gap-3 min-w-0">
            {me && me.workspaces.length > 1 ? (
              <select aria-label="Workspace" className="h-8 max-w-64 rounded-md border border-line bg-panel px-2 text-[13px] font-medium"
                value={me.tenant.id} disabled={switching}
                onChange={async (e) => { setSwitching(true); try { await switchWorkspace(e.target.value) } finally { setSwitching(false) } }}>
                {me.workspaces.map((w) => <option key={w.id} value={w.id}>{w.name}{w.primary_owner ? '' : ` · ${label(w.role)}`}</option>)}
              </select>
            ) : (
              <span className="truncate text-[13px] font-medium">{me?.tenant.name ?? '—'}</span>
            )}
            {me && <Badge tone={me.role === 'VIEWER' ? 'neutral' : me.role === 'DEVELOPER' ? 'warn' : 'good'}>{label(me.role)}</Badge>}
            {me?.tenant.demo && <Badge tone="accent">Demo workspace</Badge>}
            <span className="hidden sm:inline text-[12px] text-ink-3 font-mono truncate">{me?.tenant.slug}</span>
          </div>
          <div className="flex items-center gap-1">
            <span className="hidden lg:inline text-[12px] text-ink-3 mr-2 truncate max-w-56">{me?.email}</span>
            <button type="button" onClick={toggle} aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`} className="size-8 inline-flex items-center justify-center rounded-md text-ink-3 hover:text-ink hover:bg-panel-2">
              {theme === 'dark' ? <Sun className="size-4" /> : <Moon className="size-4" />}
            </button>
            <button type="button" onClick={signOut} aria-label="Sign out" className="size-8 inline-flex items-center justify-center rounded-md text-ink-3 hover:text-ink hover:bg-panel-2">
              <LogOut className="size-4" />
            </button>
          </div>
        </header>
        <main className="flex-1 px-5 sm:px-8 py-8 max-w-[1280px] w-full mx-auto">
          {notice && (
            <div role="status" className={`mb-6 flex items-center gap-3 rounded-md border px-4 py-2.5 text-[13px] ${notice.tone === 'good' ? 'border-good/30 bg-good-soft text-good' : 'border-bad/30 bg-bad-soft text-bad'}`}>
              <span className="flex-1">{notice.text}</span>
              <button type="button" aria-label="Dismiss" onClick={dismissNotice}><X className="size-4" /></button>
            </div>
          )}
          <ErrorBoundary label="This page">
            {/* remount pages when the workspace changes, so no page shows the previous workspace's data */}
            <Outlet key={me?.tenant.id} />
          </ErrorBoundary>
        </main>
      </div>
    </div>
  )
}
