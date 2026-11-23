import { CircleAlert, LoaderCircle, RefreshCw, X } from 'lucide-react'
import { useEffect, useRef, type ButtonHTMLAttributes, type ReactNode } from 'react'
import type { ApiError } from '../../lib/api'

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-accent text-accent-ink hover:brightness-110 border border-transparent',
  secondary: 'bg-panel text-ink border border-line hover:border-line-strong hover:bg-panel-2',
  ghost: 'text-ink-2 hover:text-ink hover:bg-panel-2 border border-transparent',
  danger: 'bg-panel text-bad border border-line hover:border-bad/50 hover:bg-bad-soft',
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  size?: 'sm' | 'md'
  loading?: boolean
  icon?: ReactNode
}

export function Button({ variant = 'secondary', size = 'md', loading, icon, children, className = '', disabled, ...rest }: ButtonProps) {
  return (
    <button
      type="button"
      {...rest}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={`inline-flex items-center justify-center gap-2 rounded-md font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed whitespace-nowrap ${
        size === 'sm' ? 'h-8 px-3 text-[13px]' : 'h-9 px-3.5 text-sm'
      } ${VARIANTS[variant]} ${className}`}
    >
      {loading ? <LoaderCircle className="size-4 animate-spin" aria-hidden /> : icon}
      {children}
    </button>
  )
}

export function Panel({ title, action, children, className = '', pad = true }: {
  title?: ReactNode
  action?: ReactNode
  children: ReactNode
  className?: string
  pad?: boolean
}) {
  return (
    <section className={`rounded-lg border border-line bg-panel ${className}`}>
      {(title || action) && (
        <header className="flex items-center justify-between gap-4 border-b border-line px-5 h-12">
          <h2 className="text-[13px] font-medium text-ink">{title}</h2>
          {action}
        </header>
      )}
      <div className={pad ? 'p-5' : ''}>{children}</div>
    </section>
  )
}

type Tone = 'neutral' | 'good' | 'warn' | 'bad' | 'accent'

const TONES: Record<Tone, string> = {
  neutral: 'text-ink-2 border-line bg-panel-2',
  good: 'text-good border-good/30 bg-good-soft',
  warn: 'text-warn border-warn/30 bg-warn-soft',
  bad: 'text-bad border-bad/30 bg-bad-soft',
  accent: 'text-accent border-accent/30 bg-accent-soft',
}

export function Badge({ tone = 'neutral', children, dot, mono }: { tone?: Tone; children: ReactNode; dot?: boolean; mono?: boolean }) {
  return (
    <span className={`inline-flex items-center gap-1.5 rounded border px-1.5 h-5 text-[11px] font-medium tracking-wide ${mono ? 'font-mono' : ''} ${TONES[tone]}`}>
      {dot && <span className="size-1.5 rounded-full bg-current" aria-hidden />}
      {children}
    </span>
  )
}

export function Skeleton({ className = '' }: { className?: string }) {
  return <div className={`skeleton ${className}`} aria-hidden />
}

export function ErrorBanner({ error, onRetry, compact }: { error: ApiError; onRetry?: () => void; compact?: boolean }) {
  return (
    <div role="alert" className={`flex items-start gap-3 rounded-md border border-bad/30 bg-bad-soft text-bad ${compact ? 'p-3' : 'p-4'}`}>
      <CircleAlert className="size-4 mt-0.5 shrink-0" aria-hidden />
      <div className="min-w-0 flex-1">
        <p className="text-[13px] font-medium">{error.title}{error.status ? <span className="font-mono font-normal opacity-70"> · {error.status}</span> : null}</p>
        <p className="text-[13px] opacity-90 break-words">{error.message}</p>
      </div>
      {onRetry && (
        <Button size="sm" variant="ghost" onClick={onRetry} icon={<RefreshCw className="size-3.5" />} className="!text-bad">
          Retry
        </Button>
      )}
    </div>
  )
}

export function EmptyState({ title, hint, action }: { title: string; hint?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center text-center py-12 px-6">
      <p className="text-sm text-ink">{title}</p>
      {hint && <p className="mt-1 text-[13px] text-ink-3 max-w-sm">{hint}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}

export function Segmented<T extends string>({ value, options, onChange, label }: {
  value: T
  options: { value: T; label: string }[]
  onChange: (v: T) => void
  label: string
}) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex rounded-md border border-line bg-panel p-0.5">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={value === o.value}
          onClick={() => onChange(o.value)}
          className={`h-7 px-3 rounded text-[13px] num transition-colors ${
            value === o.value ? 'bg-panel-2 text-ink border border-line' : 'text-ink-3 hover:text-ink border border-transparent'
          }`}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function Modal({ open, title, onClose, children, footer, wide }: {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  wide?: boolean
}) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    ref.current?.querySelector<HTMLElement>('input, button, textarea')?.focus()
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])
  if (!open) return null
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-[2px]" onClick={onClose} aria-hidden />
      <div ref={ref} role="dialog" aria-modal="true" aria-label={title} className={`relative w-full ${wide ? 'max-w-2xl' : 'max-w-md'} max-h-[90vh] overflow-y-auto rounded-lg border border-line bg-panel shadow-2xl`}>
        <header className="flex items-center justify-between border-b border-line px-5 h-12">
          <h2 className="text-sm font-medium">{title}</h2>
          <button type="button" onClick={onClose} aria-label="Close dialog" className="text-ink-3 hover:text-ink">
            <X className="size-4" />
          </button>
        </header>
        <div className="p-5">{children}</div>
        {footer && <footer className="flex justify-end gap-2 border-t border-line px-5 py-3">{footer}</footer>}
      </div>
    </div>
  )
}

export function Field({ label, hint, error, children, htmlFor }: {
  label: string
  hint?: string
  error?: string | null
  children: ReactNode
  htmlFor: string
}) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={htmlFor} className="block text-[13px] font-medium text-ink-2">{label}</label>
      {children}
      {error ? <p className="text-[12px] text-bad">{error}</p> : hint ? <p className="text-[12px] text-ink-3">{hint}</p> : null}
    </div>
  )
}

export const inputClass =
  'w-full h-9 rounded-md border border-line bg-panel px-3 text-sm text-ink placeholder:text-ink-3 outline-none transition-colors focus:border-accent focus-visible:outline-none aria-[invalid=true]:border-bad'

export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
        {description && <p className="mt-1 text-[13px] text-ink-3 max-w-2xl">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}

/** Circuit-breaker state with an icon-free text label, so colour is never the only signal. */
export function CircuitPill({ provider, state, primary, enabled, live }: {
  provider: string
  state: string
  primary: boolean
  enabled: boolean
  live: boolean
}) {
  const label = !enabled ? 'DISABLED' : state === 'OPEN' ? 'OPEN' : state === 'HALF_OPEN' ? 'HALF-OPEN' : primary ? 'CLOSED' : 'STANDBY'
  const tone: Tone = !enabled ? 'neutral' : state === 'OPEN' ? 'bad' : state === 'HALF_OPEN' ? 'warn' : primary ? 'good' : 'neutral'
  return (
    <span className="inline-flex items-center gap-2" title={live ? 'Live upstream API' : 'Built-in simulator (no API key configured)'}>
      <Badge tone={tone} dot mono>
        {provider}: {label}
      </Badge>
      {!live && <span className="text-[11px] text-ink-3">sim</span>}
    </span>
  )
}
