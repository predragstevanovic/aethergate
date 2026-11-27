import { Area, Bar, CartesianGrid, ComposedChart, Line, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { Forecast, HourPoint } from '../lib/api'
import { usd, usdSmart } from '../lib/format'

const AXIS = { fontSize: 11, fill: 'var(--ink-3)' }
const axisUsd = (v: number) => (v === 0 ? '$0' : v < 0.1 ? `$${v.toFixed(3)}` : v < 10 ? `$${v.toFixed(2)}` : `$${v.toFixed(0)}`)
const dayLabel = (iso: string) => new Date(iso + 'T00:00:00Z').toLocaleDateString('en-GB', { day: '2-digit', month: 'short', timeZone: 'UTC' })
const hourLabel = (iso: string) => new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })

function Tip({ title, rows }: { title: string; rows: { name: string; value: string; color: string }[] }) {
  return (
    <div className="rounded-md border border-line bg-panel px-3 py-2 shadow-lg text-[12px] min-w-44">
      <p className="text-ink-3 mb-1">{title}</p>
      {rows.map((r) => (
        <p key={r.name} className="flex items-center justify-between gap-6">
          <span className="flex items-center gap-2 text-ink-2"><span className="size-2 rounded-full" style={{ background: r.color }} aria-hidden />{r.name}</span>
          <span className="num font-mono text-ink">{r.value}</span>
        </p>
      ))}
    </div>
  )
}

/** Cumulative month spend: actual (solid) and projection with its 80 % band, against the hard limit. */
export function ForecastChart({ forecast }: { forecast: Forecast }) {
  const data = forecast.points.map((p) => ({
    day: p.day,
    actual: p.cumulative_usd == null ? null : Number(p.cumulative_usd),
    projected: p.projected_usd == null ? null : Number(p.projected_usd),
    band: p.low_usd == null || p.high_usd == null ? null : [Number(p.low_usd), Number(p.high_usd)],
    raw: p,
  }))
  const limit = Number(forecast.hard_limit_usd)
  const top = Math.max(limit, Number(forecast.projected_high_usd)) * 1.08
  return (
    <div className="space-y-3">
      <ul className="flex flex-wrap gap-x-5 gap-y-1 text-[12px] text-ink-2">
        <li className="flex items-center gap-2"><span className="h-0.5 w-4 bg-[var(--series-1)]" aria-hidden />Actual (cumulative)</li>
        <li className="flex items-center gap-2"><span className="h-0.5 w-4 border-t-2 border-dashed border-[var(--series-2)]" aria-hidden />Projected</li>
        <li className="flex items-center gap-2"><span className="h-2 w-4 rounded-sm bg-[var(--series-2)] opacity-25" aria-hidden />80 % band</li>
        <li className="flex items-center gap-2"><span className="h-0.5 w-4 bg-[var(--bad)]" aria-hidden />Hard limit {usd(forecast.hard_limit_usd)}</li>
      </ul>
      <div className="h-64" role="img" aria-label={`Month-to-date spend ${usd(forecast.month_to_date_usd)}, projected ${usd(forecast.projected_month_end_usd)} at month end against a ${usd(forecast.hard_limit_usd)} limit`}>
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
            <CartesianGrid stroke="var(--grid)" vertical={false} />
            <XAxis dataKey="day" tickFormatter={dayLabel} tick={AXIS} axisLine={false} tickLine={false} minTickGap={24} />
            <YAxis tickFormatter={axisUsd} tick={AXIS} axisLine={false} tickLine={false} width={48} domain={[0, top]} />
            <Tooltip cursor={{ stroke: 'var(--line-strong)' }} content={({ active, payload, label }) => {
              if (!active || !payload?.length) return null
              const p = payload[0].payload.raw as Forecast['points'][number]
              const rows = p.actual_usd != null
                ? [{ name: 'Spent that day', value: usdSmart(p.actual_usd), color: 'var(--series-1)' },
                  { name: 'Cumulative', value: usdSmart(p.cumulative_usd), color: 'var(--series-1)' }]
                : [{ name: 'Projected', value: usdSmart(p.projected_usd), color: 'var(--series-2)' },
                  { name: '80 % band', value: `${usdSmart(p.low_usd)} – ${usdSmart(p.high_usd)}`, color: 'var(--series-2)' }]
              return <Tip title={dayLabel(String(label))} rows={rows} />
            }} />
            <ReferenceLine y={limit} stroke="var(--bad)" strokeWidth={1.5} />
            <Area dataKey="band" stroke="none" fill="var(--series-2)" fillOpacity={0.18} isAnimationActive={false} connectNulls />
            <Line dataKey="actual" stroke="var(--series-1)" strokeWidth={2} dot={false} isAnimationActive={false} />
            <Line dataKey="projected" stroke="var(--series-2)" strokeWidth={2} strokeDasharray="5 4" dot={false} isAnimationActive={false} connectNulls />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}

/** Hourly spend for 48 h with the robust threshold line; anomalous hours are drawn in the alert colour. */
export function AnomalyChart({ hours }: { hours: HourPoint[] }) {
  const data = hours.map((h) => ({ ...h, cost: Number(h.cost_usd), threshold: Number(h.threshold_usd), baseline: Number(h.baseline_usd) }))
  const flagged = hours.filter((h) => h.anomalous).length
  return (
    <div className="space-y-3">
      <ul className="flex flex-wrap gap-x-5 gap-y-1 text-[12px] text-ink-2">
        <li className="flex items-center gap-2"><span className="h-2 w-3 rounded-sm bg-[var(--series-1)]" aria-hidden />Hourly spend</li>
        <li className="flex items-center gap-2"><span className="h-2 w-3 rounded-sm bg-[var(--bad)]" aria-hidden />Anomalous hour ({flagged})</li>
        <li className="flex items-center gap-2"><span className="h-0.5 w-4 border-t-2 border-dashed border-[var(--warn)]" aria-hidden />Alert threshold</li>
      </ul>
      <div className="h-52" role="img" aria-label={`Hourly spend over 48 hours, ${flagged} anomalous hours`}>
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }} barCategoryGap={1}>
            <CartesianGrid stroke="var(--grid)" vertical={false} />
            <XAxis dataKey="hour" tickFormatter={hourLabel} tick={AXIS} axisLine={false} tickLine={false} minTickGap={32} />
            <YAxis tickFormatter={axisUsd} tick={AXIS} axisLine={false} tickLine={false} width={48} />
            <Tooltip cursor={{ fill: 'var(--panel-2)' }} content={({ active, payload }) => {
              if (!active || !payload?.length) return null
              const h = payload[0].payload as HourPoint
              return <Tip title={new Date(h.hour).toLocaleString('en-GB', { weekday: 'short', hour: '2-digit', minute: '2-digit' })} rows={[
                { name: 'Spend', value: usdSmart(h.cost_usd), color: h.anomalous ? 'var(--bad)' : 'var(--series-1)' },
                { name: 'Baseline (median)', value: usdSmart(h.baseline_usd), color: 'var(--ink-3)' },
                { name: 'Threshold', value: usdSmart(h.threshold_usd), color: 'var(--warn)' },
              ]} />
            }} />
            <Bar dataKey="cost" radius={[3, 3, 0, 0]} maxBarSize={12} isAnimationActive={false}
              shape={(props: unknown) => {
                const p = props as { x: number; y: number; width: number; height: number; payload: HourPoint }
                return <rect x={p.x} y={p.y} width={p.width} height={Math.max(0, p.height)} rx={2}
                  fill={p.payload.anomalous ? 'var(--bad)' : 'var(--series-1)'} />
              }} />
            <Line dataKey="threshold" stroke="var(--warn)" strokeWidth={1.5} strokeDasharray="4 4" dot={false} isAnimationActive={false} />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}
