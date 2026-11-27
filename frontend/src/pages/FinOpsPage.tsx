import { ArrowRight, Lightbulb, Siren, TrendingUp } from 'lucide-react'
import { Link } from 'react-router'
import { AnomalyChart, ForecastChart } from '../components/finopsCharts'
import { Badge, EmptyState, ErrorBanner, PageHeader, Panel, Skeleton } from '../components/ui/primitives'
import { useResource } from '../hooks/useResource'
import { get, type Anomalies, type Forecast, type Recommendation, type Recommendations } from '../lib/api'
import { dateTime, relative, usd, usdSmart } from '../lib/format'

const STATUS: Record<Forecast['status'], { tone: 'good' | 'warn' | 'bad'; label: string }> = {
  ON_TRACK: { tone: 'good', label: 'On track' },
  AT_RISK: { tone: 'warn', label: 'At risk' },
  OVER_LIMIT: { tone: 'bad', label: 'Will exceed limit' },
}

const dayFmt = (d: string) => new Date(d + 'T00:00:00Z').toLocaleDateString('en-GB', { day: '2-digit', month: 'short', timeZone: 'UTC' })

function Stat({ label, value, sub }: { label: string; value: string; sub?: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-line bg-panel p-4">
      <p className="text-[12px] text-ink-3">{label}</p>
      <p className="mt-1 text-[22px] font-semibold tracking-tight num">{value}</p>
      {sub && <div className="mt-1 text-[12px] text-ink-3">{sub}</div>}
    </div>
  )
}

export function FinOpsPage() {
  return (
    <div className="space-y-8">
      <PageHeader
        title="FinOps"
        description="Month-end forecast from the ledger, robust anomaly detection on hourly spend, and a savings advisor that prices its recommendations from your real token counts and catalog prices."
      />
      <ForecastSection />
      <AnomalySection />
      <AdvisorSection />
    </div>
  )
}

function ForecastSection() {
  const f = useResource(() => get<Forecast>('/api/v1/analytics/forecast'), [], 60_000)
  const d = f.data
  return (
    <section className="space-y-4" aria-labelledby="forecast-h">
      <div className="flex items-center gap-3">
        <h2 id="forecast-h" className="text-sm font-medium inline-flex items-center gap-2"><TrendingUp className="size-4" aria-hidden />Spend forecast · {d?.period ?? '…'}</h2>
        {d && <Badge tone={STATUS[d.status].tone} dot>{STATUS[d.status].label}</Badge>}
      </div>
      {f.error && <ErrorBanner error={f.error} onRetry={f.reload} />}
      {!d ? (
        <div className="grid gap-4 sm:grid-cols-4">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-24" />)}</div>
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Stat label="Month to date (settled)" value={usd(d.month_to_date_usd)} sub={`day ${d.elapsed_days.toFixed(1)} of ${d.days_in_month}`} />
            <Stat label="Projected month end" value={usd(d.projected_month_end_usd)}
              sub={<span className="num">{usd(d.projected_low_usd)} – {usd(d.projected_high_usd)} (80 %) · {d.projected_pct_of_limit.toFixed(0)} % of limit</span>} />
            <Stat label="Daily run rate (7 d)" value={usdSmart(d.daily_run_rate_usd)}
              sub={<span className={d.trend_pct > 0 ? 'text-warn' : 'text-good'}>{d.trend_pct >= 0 ? '+' : ''}{d.trend_pct.toFixed(0)} % vs previous 7 d</span>} />
            <Stat label="Credit runway" value={d.balance_runway_days == null ? '∞' : `${Math.floor(d.balance_runway_days)} days`}
              sub={d.limit_reached_on ? <span className="text-bad">Limit reached ≈ {dayFmt(d.limit_reached_on)}</span>
                : d.balance_exhausted_on ? `Balance ${usd(d.available_balance_usd)} lasts to ${dayFmt(d.balance_exhausted_on)}` : `Balance ${usd(d.available_balance_usd)}`} />
          </div>
          <Panel title="Cumulative spend vs hard limit">
            <ForecastChart forecast={d} />
            <p className="mt-3 text-[12px] text-ink-3">
              Projection = month-to-date + 7-day run rate × remaining days; band = ±1.28 σ of the last 14 daily totals × √days.
              The naive linear projection would be {usd(d.linear_projection_usd)}.
            </p>
          </Panel>
          {d.projects.length > 0 && (
            <Panel pad={false} title="Projects at month end">
              <div className="overflow-x-auto">
                <table className="w-full text-[13px]">
                  <thead><tr className="text-left text-[12px] text-ink-3 border-b border-line">
                    <th className="font-normal px-5 h-9">Project</th><th className="font-normal px-3 text-right">MTD</th>
                    <th className="font-normal px-3 text-right">Run rate / day</th><th className="font-normal px-3 text-right">Projected</th>
                    <th className="font-normal px-3 text-right">Budget</th><th className="font-normal px-5">Outlook</th>
                  </tr></thead>
                  <tbody>
                    {d.projects.map((p) => (
                      <tr key={p.project_id} className="border-b border-line last:border-0">
                        <td className="px-5 py-2.5">{p.name}</td>
                        <td className="px-3 text-right font-mono num">{usdSmart(p.month_to_date_usd)}</td>
                        <td className="px-3 text-right font-mono num text-ink-2">{usdSmart(p.daily_run_rate_usd)}</td>
                        <td className="px-3 text-right font-mono num">{usdSmart(p.projected_usd)}</td>
                        <td className="px-3 text-right font-mono num text-ink-2">{p.budget_usd ? usd(p.budget_usd) : '—'}</td>
                        <td className="px-5">
                          {p.status === 'NO_BUDGET' ? <Badge>No budget</Badge>
                            : <Badge tone={p.status === 'ON_TRACK' ? 'good' : p.status === 'AT_RISK' ? 'warn' : 'bad'} dot>{p.projected_pct?.toFixed(0)} %</Badge>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Panel>
          )}
        </>
      )}
    </section>
  )
}

function AnomalySection() {
  const a = useResource(() => get<Anomalies>('/api/v1/analytics/anomalies'), [], 60_000)
  return (
    <Panel title={<span className="inline-flex items-center gap-2"><Siren className="size-4" aria-hidden />Cost anomalies · last 48 h</span>}
      action={a.data && <span className="text-[12px] text-ink-3">z ≥ {a.data.z_threshold} vs previous 168 h</span>}>
      {a.error && <ErrorBanner error={a.error} onRetry={a.reload} />}
      {!a.data ? <Skeleton className="h-52" /> : (
        <div className="space-y-5">
          <AnomalyChart hours={a.data.hours} />
          {a.data.anomalies.length === 0 ? (
            <p className="text-[13px] text-ink-3">No anomalies in the last 30 days. Each detected hour is stored once and sent as a <span className="font-mono">cost.anomaly_detected</span> webhook.</p>
          ) : (
            <ul className="divide-y divide-line border-t border-line">
              {a.data.anomalies.slice(0, 8).map((x) => (
                <li key={x.bucket_start} className="flex flex-wrap items-center gap-3 py-2.5 text-[13px]">
                  <Badge tone={x.severity === 'CRITICAL' ? 'bad' : 'warn'} dot>{x.severity}</Badge>
                  <span className="text-ink-2" title={dateTime(x.bucket_start)}>{new Date(x.bucket_start).toLocaleString('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' })}</span>
                  <span className="font-mono num">{usdSmart(x.observed_usd)}</span>
                  <span className="text-ink-3 num">vs baseline {usdSmart(x.baseline_usd)} · z {x.z_score.toFixed(1)}</span>
                  {x.top_dimension && <span className="text-ink-2 truncate max-w-md">{x.top_dimension}</span>}
                  <span className="ml-auto text-[12px] text-ink-3">detected {relative(x.detected_at)}</span>
                </li>
              ))}
            </ul>
          )}
          <p className="text-[12px] text-ink-3">{a.data.method}. Median and MAD ignore the spike itself, so one runaway hour cannot raise its own bar.</p>
        </div>
      )}
    </Panel>
  )
}

const CATEGORY_TONE: Record<Recommendation['category'], 'accent' | 'warn' | 'neutral' | 'bad'> = {
  COST: 'accent', RELIABILITY: 'warn', GOVERNANCE: 'neutral', SECURITY: 'bad',
}

function AdvisorSection() {
  const r = useResource(() => get<Recommendations>('/api/v1/analytics/recommendations'), [])
  return (
    <Panel pad={false} title={<span className="inline-flex items-center gap-2"><Lightbulb className="size-4" aria-hidden />Savings advisor</span>}
      action={r.data && <span className="text-[12px] text-ink-3 num">≈ {usd(r.data.total_monthly_savings_usd)}/mo identified · 30-day spend {usd(r.data.last30d_spend_usd)}</span>}>
      {r.error && <div className="p-5"><ErrorBanner error={r.error} onRetry={r.reload} /></div>}
      {!r.data ? <div className="p-5 space-y-2">{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-14" />)}</div>
        : !r.data.items.length ? <EmptyState title="Nothing to improve right now" hint="Recommendations appear once there is traffic to analyse." />
        : (
          <ul className="divide-y divide-line">
            {r.data.items.map((x) => (
              <li key={x.id} className="px-5 py-4 flex flex-col sm:flex-row gap-3 sm:items-start">
                <div className="flex-1 min-w-0 space-y-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={CATEGORY_TONE[x.category]}>{x.category}</Badge>
                    {x.severity === 'HIGH' && <Badge tone="warn">High impact</Badge>}
                    <span className="text-[13px] font-medium">{x.title}</span>
                  </div>
                  <p className="text-[13px] text-ink-3">{x.detail}</p>
                </div>
                <div className="flex sm:flex-col items-center sm:items-end gap-2 shrink-0">
                  {x.monthly_savings_usd && <span className="text-[15px] font-semibold text-good num">−{usdSmart(x.monthly_savings_usd)}/mo</span>}
                  {x.action && (
                    <Link to={x.action.href} className="inline-flex items-center gap-1 text-[12px] text-accent hover:underline underline-offset-4">
                      {x.action.label}<ArrowRight className="size-3" aria-hidden />
                    </Link>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
    </Panel>
  )
}
