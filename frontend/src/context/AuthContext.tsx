import type { Session } from '@supabase/supabase-js'
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { ApiError, get, post, type Me } from '../lib/api'
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
}

const AuthContext = createContext<AuthState | null>(null)

/** Supabase session + the workspace (tenant) it is bound to on the gateway. */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null)
  const [me, setMe] = useState<Me | null>(null)
  const [sessionLoaded, setSessionLoaded] = useState(false)
  const [meFor, setMeFor] = useState<string | null>(null) // user id the workspace lookup finished for

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
    get<Me>('/api/v1/me')
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
    await supabase.auth.signOut()
  }, [])

  const value = useMemo(
    () => ({ session, me, ready, signIn, signUp, signInDemo, signOut }),
    [session, me, ready, signIn, signUp, signInDemo, signOut],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>')
  return ctx
}
