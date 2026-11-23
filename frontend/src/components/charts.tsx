import { Area, AreaChart, Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { SeriesPoint } from '../lib/api'
import { bucketLabel, tokens, usd8, usdSmart } from '../lib/format'

const AXIS = { fontSize: 11, fill: 'var(--ink-3)' }

const axisTokens = (v: number) => (v >= 1_000_000 ? `${+(v / 1_000_000).toFixed(1)}M` : v >= 1000 ? `${+(v / 1000).toFixed(1)}k` : String(v))
const axisUsd = (v: number) => (v === 0 ? '$0' : v < 0.1 ? `$${v.toFixed(3)}` : v < 10 ? `$${v.toFixed(2)}` : `$${v.toFixed(0)}`)

function Legend({ items }: { items: { color: string; label: string; value?: string }[] }) {
  return (
    <ul className="flex flex-wrap gap-x-5 gap-y-1 text-[12px] text-ink-2">
      {items.map((i) => (
        <li key={i.label} className="flex items-center gap-2">
          <span className="h-2 w-3 rounded-sm" style={{ background: i.color }} aria-hidden />
          {i.label}
          {i.value && <span className="num text-ink">{i.value}</span>}
        </li>
      ))}
    </ul>
  )
}

interface TipRow { name: string; color: string; value: string }

function TooltipCard({ title, rows }: { title: string; rows: TipRow[] }) {
  return (
    <div className="rounded-md border border-line bg-panel px-3 py-2 shadow-lg text-[12px] min-w-40">
      <p className="text-ink-3 mb-1">{title}</p>
      {rows.map((r) => (
        <p key={r.name} className="flex items-center justify-between gap-6">
          <span className="flex items-center gap-2 text-ink-2">
            <span className="size-2 rounded-full" style={{ background: r.color }} aria-hidden />
            {r.name}
          </span>
          <span className="num font-mono text-ink">{r.value}</span>
        </p>
      ))}
    </div>
  )
}

/** Token consumption per bucket: prompt vs completion, stacked. */
export function TokenChart({ series, range }: { series: SeriesPoint[]; range: string }) {
  const totalPrompt = series.reduce((s, p) => s + p.prompt_tokens, 0)
  const totalCompletion = series.reduce((s, p) => s + p.completion_tokens, 0)
  return (
    <div className="space-y-4">
      <Legend items={[
        { color: 'var(--series-1)', label: 'Prompt tokens', value: tokens(totalPrompt) },
        { color: 'var(--series-2)', label: 'Completion tokens', value: tokens(totalCompletion) },
      ]} />
      <div className="h-56" role="img" aria-label={`Token consumption over ${range}: ${totalPrompt} prompt and ${totalCompletion} completion tokens`}>
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={series} margin={{ top: 4, right: 4, bottom: 0, left: 0 }}>
            <CartesianGrid stroke="var(--grid)" vertical={false} />
            <XAxis dataKey="bucket" tickFormatter={(v: string) => bucketLabel(v, range)} tick={AXIS} axisLine={false} tickLine={false} minTickGap={28} />
            <YAxis tickFormatter={axisTokens} tick={AXIS} axisLine={false} tickLine={false} width={44} />
            <Tooltip
              cursor={{ stroke: 'var(--line-strong)', strokeWidth: 1 }}
              content={({ active, payload, label }) =>
                active && payload?.length ? (
                  <TooltipCard title={bucketLabel(String(label), range)} rows={[
                    { name: 'Prompt', color: 'var(--series-1)', value: tokens(Number(payload[0]?.payload.prompt_tokens)) },
                    { name: 'Completion', color: 'var(--series-2)', value: tokens(Number(payload[0]?.payload.completion_tokens)) },
                    { name: 'Requests', color: 'var(--ink-3)', value: String(payload[0]?.payload.requests) },
                  ]} />
                ) : null
              }
            />
            <Area type="monotone" dataKey="prompt_tokens" stackId="t" stroke="var(--series-1)" strokeWidth={2} fill="var(--series-1)" fillOpacity={0.16} isAnimationActive={false} />
            <Area type="monotone" dataKey="completion_tokens" stackId="t" stroke="var(--series-2)" strokeWidth={2} fill="var(--series-2)" fillOpacity={0.16} isAnimationActive={false} />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}

/** Cost burn rate per bucket (single series: the title names it, no legend box). */
export function BurnChart({ series, range }: { series: SeriesPoint[]; range: string }) {
  const data = series.map((p) => ({ ...p, cost: Number(p.cost_usd) }))
  return (
    <div className="h-56" role="img" aria-label={`Cost burn rate over ${range}`}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 4, right: 4, bottom: 0, left: 0 }} barCategoryGap={2}>
          <CartesianGrid stroke="var(--grid)" vertical={false} />
          <XAxis dataKey="bucket" tickFormatter={(v: string) => bucketLabel(v, range)} tick={AXIS} axisLine={false} tickLine={false} minTickGap={28} />
          <YAxis tickFormatter={axisUsd} tick={AXIS} axisLine={false} tickLine={false} width={44} />
          <Tooltip
            cursor={{ fill: 'var(--panel-2)' }}
            content={({ active, payload, label }) =>
              active && payload?.length ? (
                <TooltipCard title={bucketLabel(String(label), range)} rows={[
                  { name: 'Spend', color: 'var(--series-1)', value: usd8(String(payload[0]?.payload.cost_usd)) },
                  { name: 'Cache saved', color: 'var(--ink-3)', value: usdSmart(String(payload[0]?.payload.saved_usd)) },
                  { name: 'Cache hits', color: 'var(--ink-3)', value: `${payload[0]?.payload.cache_hits} / ${payload[0]?.payload.requests}` },
                ]} />
              ) : null
            }
          />
          <Bar dataKey="cost" fill="var(--series-1)" radius={[4, 4, 0, 0]} maxBarSize={18} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
