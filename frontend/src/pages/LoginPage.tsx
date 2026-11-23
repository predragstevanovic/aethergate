import { ArrowRight } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Navigate } from 'react-router'
import { Logo } from '../components/layout/Logo'
import { Button, ErrorBanner, Field, inputClass } from '../components/ui/primitives'
import { useAuth } from '../context/AuthContext'
import { useAsyncAction } from '../hooks/useInteractions'
import { supabaseConfigured } from '../lib/supabase'

type Mode = 'signin' | 'signup'

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export function LoginPage() {
  const { session, me, signIn, signUp, signInDemo } = useAuth()
  const [mode, setMode] = useState<Mode>('signin')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [touched, setTouched] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  const submit = useAsyncAction(async () => {
    if (mode === 'signin') {
      await signIn(email.trim(), password)
    } else {
      const { needsConfirmation } = await signUp(email.trim(), password)
      if (needsConfirmation) setNotice('Check your inbox to confirm your email, then sign in.')
    }
  })
  const demo = useAsyncAction(signInDemo)

  if (session && me) return <Navigate to="/" replace />

  const emailError = touched && !EMAIL_RE.test(email.trim()) ? 'Enter a valid email address' : null
  const passwordError = touched && password.length < 8 ? 'At least 8 characters' : null

  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    setTouched(true)
    setNotice(null)
    if (!EMAIL_RE.test(email.trim()) || password.length < 8) return
    void submit.run()
  }

  const error = submit.error ?? demo.error

  return (
    <div className="min-h-full grid lg:grid-cols-[1.1fr_1fr]">
      <aside className="hidden lg:flex flex-col justify-between border-r border-line bg-panel p-12">
        <div className="flex items-center gap-2.5">
          <Logo className="size-7" />
          <span className="font-semibold tracking-tight">AetherGate</span>
        </div>
        <div className="max-w-md">
          <h1 className="text-[32px] leading-[1.15] font-semibold tracking-tight">
            Deterministic token metering &amp; resilient AI routing.
          </h1>
          <p className="mt-4 text-ink-2">
            One OpenAI-compatible gateway in front of every LLM provider: pre-flight budget holds, exact
            micro-cent settlement, semantic caching on pgvector and circuit-breaker failover, isolated per tenant
            by PostgreSQL Row-Level Security.
          </p>
          <dl className="mt-10 grid grid-cols-3 gap-6 border-t border-line pt-6">
            {[
              ['RESERVE → SETTLE', 'two-phase ledger'],
              ['< 20 ms', 'cache-hit replay'],
              ['0 rows', 'cross-tenant leakage'],
            ].map(([k, v]) => (
              <div key={k}>
                <dt className="font-mono text-[13px] text-ink num">{k}</dt>
                <dd className="mt-1 text-[12px] text-ink-3">{v}</dd>
              </div>
            ))}
          </dl>
        </div>
        <p className="text-[12px] text-ink-3">Java 21 virtual threads · Spring Boot · Supabase PostgreSQL · React</p>
      </aside>

      <main className="flex items-center justify-center p-6 sm:p-12">
        <div className="w-full max-w-sm">
          <div className="lg:hidden flex items-center gap-2.5 mb-10">
            <Logo className="size-7" />
            <span className="font-semibold tracking-tight">AetherGate</span>
          </div>
          <h2 className="text-xl font-semibold tracking-tight">{mode === 'signin' ? 'Sign in to the console' : 'Create a workspace'}</h2>
          <p className="mt-1 text-[13px] text-ink-3">
            {mode === 'signin' ? 'Use your email, or explore the pre-seeded demo.' : 'New workspaces start with $100.00 of credit.'}
          </p>

          <Button
            variant="secondary"
            className="w-full mt-8 h-10"
            loading={demo.pending}
            disabled={submit.pending}
            onClick={() => void demo.run()}
            icon={!demo.pending ? <ArrowRight className="size-4" /> : undefined}
          >
            Continue with Demo Workspace
          </Button>
          <p className="mt-2 text-[12px] text-ink-3 text-center">Vortex AI Labs: 30 days of real metering history, no signup.</p>

          <div className="my-8 flex items-center gap-3 text-[12px] text-ink-3">
            <span className="h-px flex-1 bg-line" />
            or with email
            <span className="h-px flex-1 bg-line" />
          </div>

          <form onSubmit={onSubmit} noValidate className="space-y-4">
            <Field label="Email" htmlFor="email" error={emailError}>
              <input id="email" type="email" autoComplete="email" className={inputClass} value={email}
                aria-invalid={Boolean(emailError)} onChange={(e) => setEmail(e.target.value)} placeholder="you@company.com" />
            </Field>
            <Field label="Password" htmlFor="password" error={passwordError}>
              <input id="password" type="password" autoComplete={mode === 'signin' ? 'current-password' : 'new-password'}
                className={inputClass} value={password} aria-invalid={Boolean(passwordError)}
                onChange={(e) => setPassword(e.target.value)} placeholder="••••••••" />
            </Field>
            {error && <ErrorBanner error={error} compact />}
            {notice && <p role="status" className="rounded-md border border-good/30 bg-good-soft p-3 text-[13px] text-good">{notice}</p>}
            <Button type="submit" variant="primary" className="w-full h-10" loading={submit.pending} disabled={demo.pending || !supabaseConfigured}>
              {mode === 'signin' ? 'Sign in' : 'Create account'}
            </Button>
          </form>

          <p className="mt-6 text-center text-[13px] text-ink-3">
            {mode === 'signin' ? 'No account yet?' : 'Already have an account?'}{' '}
            <button type="button" className="text-ink underline-offset-4 hover:underline"
              onClick={() => { setMode(mode === 'signin' ? 'signup' : 'signin'); setTouched(false); setNotice(null) }}>
              {mode === 'signin' ? 'Create one' : 'Sign in'}
            </button>
          </p>
        </div>
      </main>
    </div>
  )
}
