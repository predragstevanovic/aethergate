/** Geometric AetherGate emblem: a gate (outer hexagon) around a metered core. */
export function Logo({ className = '' }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" className={className} aria-hidden>
      <path d="M16 3.5 26.8 9.75v12.5L16 28.5 5.2 22.25V9.75z" fill="none" stroke="var(--accent)" strokeWidth="1.75" />
      <path d="M16 10.5 21 13.4v5.2L16 21.5 11 18.6v-5.2z" fill="var(--accent)" />
      <path d="M16 3.5v7M16 21.5v7" stroke="var(--accent)" strokeWidth="1.75" opacity="0.45" />
    </svg>
  )
}
