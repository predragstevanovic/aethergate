/**
 * Money arrives from the API as decimal strings ("0.00142000"). Formatting works on the
 * string itself, so no value ever passes through a binary float.
 */
function splitDecimal(value: string): { neg: boolean; int: string; frac: string } {
  const v = value.trim()
  const neg = v.startsWith('-')
  const body = neg ? v.slice(1) : v
  const [int = '0', frac = ''] = body.split('.')
  return { neg, int: int.replace(/^0+(?=\d)/, ''), frac }
}

function groupThousands(int: string) {
  return int.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/** Exact micro-cent display: $0.00142000 (8 decimals, never rounded). */
export function usd8(value: string | null | undefined): string {
  if (value == null) return '—'
  const { neg, int, frac } = splitDecimal(value)
  return `${neg ? '−' : ''}$${groupThousands(int)}.${frac.padEnd(8, '0').slice(0, 8)}`
}

/** Display rounding (half-up on the decimal string) for headline figures: $140.63. */
export function usd(value: string | null | undefined, decimals = 2): string {
  if (value == null) return '—'
  const { neg, int, frac } = splitDecimal(value)
  const padded = frac.padEnd(decimals + 1, '0')
  let keep = padded.slice(0, decimals)
  let whole = int
  if (Number(padded[decimals]) >= 5) {
    const digits = (whole + keep).split('').map(Number)
    let i = digits.length - 1
    while (i >= 0) {
      if (digits[i] === 9) { digits[i] = 0; i-- } else { digits[i]++; break }
    }
    if (i < 0) digits.unshift(1)
    const joined = digits.join('')
    whole = joined.slice(0, joined.length - decimals) || '0'
    keep = joined.slice(joined.length - decimals)
  }
  return `${neg ? '−' : ''}$${groupThousands(whole)}${decimals > 0 ? '.' + keep : ''}`
}

/** Compact money for tiny amounts: keeps significant micro-cents ($0.0142 instead of $0.01). */
export function usdSmart(value: string | null | undefined): string {
  if (value == null) return '—'
  const n = Number(value)
  if (n !== 0 && Math.abs(n) < 1) return usd(value, 4)
  return usd(value, 2)
}

export function tokens(n: number | null | undefined): string {
  if (n == null) return '—'
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(n >= 10_000_000 ? 0 : 1)}M`
  if (n >= 10_000) return `${(n / 1_000).toFixed(0)}k`
  return n.toLocaleString('en-US')
}

export function int(n: number | null | undefined): string {
  return n == null ? '—' : n.toLocaleString('en-US')
}

export function ms(n: number | null | undefined): string {
  return n == null ? '—' : `${n.toLocaleString('en-US')} ms`
}

export function percent(ratio: number | null | undefined, digits = 1): string {
  return ratio == null ? '—' : `${(ratio * 100).toFixed(digits)}%`
}

export function dateTime(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleString('en-GB', { month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

export function relative(iso: string): string {
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000)
  if (s < 60) return `${Math.max(s, 0)}s ago`
  if (s < 3600) return `${Math.floor(s / 60)}m ago`
  if (s < 86_400) return `${Math.floor(s / 3600)}h ago`
  return `${Math.floor(s / 86_400)}d ago`
}

export function bucketLabel(iso: string, range: string): string {
  const d = new Date(iso)
  if (range === '24h') return d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })
  if (range === '7d') return d.toLocaleString('en-GB', { weekday: 'short', hour: '2-digit' })
  return d.toLocaleDateString('en-GB', { month: 'short', day: '2-digit' })
}
