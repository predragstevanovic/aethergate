import { lazy, Suspense } from 'react'
import { createBrowserRouter, Navigate, Outlet, RouterProvider } from 'react-router'
import { AppShell } from './components/layout/AppShell'
import { ErrorBoundary } from './components/layout/ErrorBoundary'
import { Logo } from './components/layout/Logo'
import { AuthProvider, useAuth } from './context/AuthContext'
import { LoginPage } from './pages/LoginPage'

// Console pages load on demand: the login screen never pulls in the charting library.
const OverviewPage = lazy(() => import('./pages/OverviewPage').then((m) => ({ default: m.OverviewPage })))
const PlaygroundChaosPage = lazy(() => import('./pages/PlaygroundChaosPage').then((m) => ({ default: m.PlaygroundChaosPage })))
const LedgerAuditPage = lazy(() => import('./pages/LedgerAuditPage').then((m) => ({ default: m.LedgerAuditPage })))
const GuardrailsPage = lazy(() => import('./pages/GuardrailsPage').then((m) => ({ default: m.GuardrailsPage })))
const ProjectsPage = lazy(() => import('./pages/ProjectsPage').then((m) => ({ default: m.ProjectsPage })))
const TeamPage = lazy(() => import('./pages/TeamPage').then((m) => ({ default: m.TeamPage })))
const AlertsPage = lazy(() => import('./pages/AlertsPage').then((m) => ({ default: m.AlertsPage })))
const PromptsPage = lazy(() => import('./pages/PromptsPage').then((m) => ({ default: m.PromptsPage })))
const FinOpsPage = lazy(() => import('./pages/FinOpsPage').then((m) => ({ default: m.FinOpsPage })))
const CostAllocationPage = lazy(() => import('./pages/CostAllocationPage').then((m) => ({ default: m.CostAllocationPage })))
const SessionsPage = lazy(() => import('./pages/SessionsPage').then((m) => ({ default: m.SessionsPage })))
const RequestsPage = lazy(() => import('./pages/RequestsPage').then((m) => ({ default: m.RequestsPage })))
const ApiKeysRoutingPage = lazy(() => import('./pages/ApiKeysRoutingPage').then((m) => ({ default: m.ApiKeysRoutingPage })))

function PageFallback() {
  return <div className="space-y-4" aria-hidden><div className="skeleton h-8 w-48" /><div className="skeleton h-28" /><div className="skeleton h-64" /></div>
}

const page = (el: React.ReactNode) => <Suspense fallback={<PageFallback />}>{el}</Suspense>

function Splash() {
  return (
    <div className="h-full flex items-center justify-center" role="status" aria-label="Loading">
      <Logo className="size-8 animate-pulse" />
    </div>
  )
}

/** Signed in + workspace resolved, or back to /login. */
function RequireAuth() {
  const { ready, session, me } = useAuth()
  if (!ready) return <Splash />
  if (!session || !me) return <Navigate to="/login" replace />
  return <Outlet />
}

function PublicOnly() {
  const { ready, session, me } = useAuth()
  if (!ready) return <Splash />
  if (session && me) return <Navigate to="/" replace />
  return <Outlet />
}

const router = createBrowserRouter([
  { element: <PublicOnly />, children: [{ path: '/login', element: <LoginPage /> }] },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppShell />,
        children: [
          { path: '/', element: page(<OverviewPage />) },
          { path: '/playground', element: page(<PlaygroundChaosPage />) },
          { path: '/ledger', element: page(<LedgerAuditPage />) },
          { path: '/finops', element: page(<FinOpsPage />) },
          { path: '/allocation', element: page(<CostAllocationPage />) },
          { path: '/sessions', element: page(<SessionsPage />) },
          { path: '/requests', element: page(<RequestsPage />) },
          { path: '/projects', element: page(<ProjectsPage />) },
          { path: '/guardrails', element: page(<GuardrailsPage />) },
          { path: '/settings', element: page(<ApiKeysRoutingPage />) },
          { path: '/prompts', element: page(<PromptsPage />) },
          { path: '/alerts', element: page(<AlertsPage />) },
          { path: '/team', element: page(<TeamPage />) },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
])

export default function App() {
  return (
    <ErrorBoundary label="AetherGate">
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </ErrorBoundary>
  )
}
