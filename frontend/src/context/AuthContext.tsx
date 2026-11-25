import type { Session } from '@supabase/supabase-js'
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { ApiError, get, post, selectWorkspace, selectedWorkspace, type Me } from '../lib/api'
import { supabase } from '../lib/supabase'

interface DemoSession {
  access_token: string
  refresh_token: string
}

interface AuthState {
  session: Session | null
  me: Me | null
  ready: boolean
  signIn: (email: string, password: string) => Promise<void>
  signUp: (email: string, password: string) => Promise<{ needsConfirmation: boolean }>
  signInDemo: () => Promise<void>
  signOut: () => Promise<void>
  /** Re-reads /api/v1/me, e.g. after the workspace was renamed. */
  refreshMe: () => Promise<void>
  /** Acts in another workspace the user belongs to (own or joined). */
  switchWorkspace: (id: string) => Promise<void>
  /** Outcome of an invitation link opened in this tab. */
  notice: { tone: 'good' | 'bad'; text: string } | null
  dismissNotice: () => void
}

const INVITE_KEY = 'aethergate.invite'

/** ?invite=<token> survives the trip through the login page in sessionStorage. */
function captureInvite() {
  try {
    const url = new URL(window.location.href)
    const token = url.searchParams.get('invite')
    if (token) {
      sessionStorage.setItem(INVITE_KEY, token)
      url.searchParams.delete('invite')
      window.history.replaceState(null, '', url.pathname + url.search + url.hash)
    }
  } catch {
    /* ignore */
  }
}
captureInvite()

const AuthContext = createContext<AuthState | null>(null)

/** Supabase session + the workspace (tenant) it is bound to on the gateway. */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null)
  const [me, setMe] = useState<Me | null>(null)
  const [sessionLoaded, setSessionLoaded] = useState(false)
  const [meFor, setMeFor] = useState<string | null>(null) // user id the workspace lookup finished for
  const [notice, setNotice] = useState<{ tone: 'good' | 'bad'; text: string } | null>(null)

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => {
      setSession(data.session)
      setSessionLoaded(true)
    })
    const { data } = supabase.auth.onAuthStateChange((_event, s) => {
      setSession(s)
      if (!s) setMe(null)
    })
    return () => data.subscription.unsubscribe()
  }, [])

  const userId = session?.user.id
  useEffect(() => {
    if (!userId) return
    let cancelled = false
    const load = async () => {
      const invite = sessionStorage.getItem(INVITE_KEY)
      if (invite) {
        sessionStorage.removeItem(INVITE_KEY)
        try {
          const joined = await post<{ tenant_id: string; tenant_name: string; role: string }>('/api/v1/invitations/accept', { token: invite })
          selectWorkspace(joined.tenant_id)
          setNotice({ tone: 'good', text: `You joined ${joined.tenant_name} as ${joined.role.toLowerCase()}.` })
        } catch (e) {
          setNotice({ tone: 'bad', text: e instanceof ApiError ? e.message : 'The invitation could not be accepted.' })
        }
      }
      const m = await get<Me>('/api/v1/me')
      if (m.workspace_fallback || (selectedWorkspace() && !m.workspaces.some((w) => w.id === selectedWorkspace()))) {
        selectWorkspace(null) // removed from that workspace: back to the default one
      }
      return m
    }
    load()
      .then((m) => !cancelled && setMe(m))
      .catch(() => !cancelled && setMe(null))
      .finally(() => !cancelled && setMeFor(userId))
    return () => {
      cancelled = true
    }
  }, [userId])

  // ready = we know whether there is a session, and (if there is) the workspace lookup finished
  const ready = sessionLoaded && (!userId || meFor === userId)

  const signIn = useCallback(async (email: string, password: string) => {
    const { error } = await supabase.auth.signInWithPassword({ email, password })
    if (error) throw new ApiError(error.status ?? 400, { title: 'Sign-in failed', detail: error.message })
  }, [])

  const signUp = useCallback(async (email: string, password: string) => {
    const { data, error } = await supabase.auth.signUp({ email, password })
    if (error) throw new ApiError(error.status ?? 400, { title: 'Sign-up failed', detail: error.message })
    return { needsConfirmation: !data.session }
  }, [])

  const signInDemo = useCallback(async () => {
    const s = await post<DemoSession>('/api/v1/auth/demo-session')
    const { error } = await supabase.auth.setSession({ access_token: s.access_token, refresh_token: s.refresh_token })
    if (error) throw new ApiError(400, { title: 'Demo login failed', detail: error.message })
  }, [])

  const signOut = useCallback(async () => {
    selectWorkspace(null)
    await supabase.auth.signOut()
  }, [])

  const refreshMe = useCallback(async () => {
    setMe(await get<Me>('/api/v1/me'))
  }, [])

  const switchWorkspace = useCallback(async (id: string) => {
    selectWorkspace(id)
    setMe(await get<Me>('/api/v1/me'))
  }, [])

  const dismissNotice = useCallback(() => setNotice(null), [])

  const value = useMemo(
    () => ({ session, me, ready, signIn, signUp, signInDemo, signOut, refreshMe, switchWorkspace, notice, dismissNotice }),
    [session, me, ready, signIn, signUp, signInDemo, signOut, refreshMe, switchWorkspace, notice, dismissNotice],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>')
  return ctx
}
