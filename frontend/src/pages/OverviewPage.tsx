import { ShieldCheck } from 'lucide-react'
import { useSearchParams } from 'react-router'
import { BurnChart, TokenChart } from '../components/charts'
import { ErrorBoundary } from '../components/layout/ErrorBoundary'
import { Badge, CircuitPill, EmptyState, ErrorBanner, PageHeader, Panel, Segmented, Skeleton } from '../components/ui/primitives'
import { useResource } from '../hooks/useResource'
import { get, type Overview, type RequestRow } from '../lib/api'
import { int, ms, percent, relative, tokens, usd, usd8, usdSmart } from '../lib/format'

type Range = '24h' | '7d' | '30d'
const RANGES: { value: Range; label: string }[] = [
  { value: '24h', label: '24h' },
  { value: '7d', label: '7d' },
  { value: '30d', label: '30d' },
]

function Kpi({ label, value, context, loading }: { label: string; value: string; context: React.ReactNode; loading: boolean }) {
  return (
    <div className="rounded-lg border border-line bg-panel p-5">
      <p className="text-[12px] text-ink-3">{label}</p>
      {loading ? <Skeleton className="h-8 w-32 mt-2" /> : <p className="mt-1.5 text-[26px] font-semibold tracking-tight num">{value}</p>}
      <div className="mt-2 text-[12px] text-ink-3 min-h-[18px]">{loading ? <Skeleton className="h-3 w-40" /> : context}</div>
    </div>
  )
}

export function OverviewPage() {
  const [params, setParams] = useSearchParams()
  const range = (RANGES.some((r) => r.value === params.get('range')) ? params.get('range') : '24h') as Range
  const { data, error, loading, refreshing, reload } = useResource(
    () => get<Overview>(`/api/v1/dashboard/overview?range=${range}`),
    [range],
    15_000,
  )
  const k = data?.kpis
  const primary = data?.providers.find((p) => p.enabled)

  return (
    <div className="space-y-8">
      <PageHeader
        title="Overview"
        description="Live burn rate, cache efficiency and routing health for this workspace. Refreshes every 15 seconds."
        actions={
          <>
            {refreshing && <span className="text-[12px] text-ink-3">updating…</span>}
            <Segmented label="Time range" value={range} options={RANGES} onChange={(r) => setParams({ range: r })} />
          </>
        }
      />

      {error && <ErrorBanner error={error} onRetry={reload} />}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Kpi label="Available credit" loading={loading} value={usd(k?.available_balance_usd)}
          context={<>Month to date <span className="num text-ink-2">{usd(k?.month_to_date_spend_usd)}</span> of {usd(k?.hard_budget_limit_usd, 0)} limit</>} />
        <Kpi label="Reserved in-flight" loading={loading} value={usd8(k?.reserved_balance_usd)}
          context="Pre-flight holds of streams still running" />
        <Kpi label="Semantic cache savings" loading={loading} value={usdSmart(k?.cache_savings_usd)}
          context={<><span className="num text-ink-2">{percent(k?.cache_hit_ratio)}</span> hit ratio · {int(k?.cache_hits)} of {int(k?.requests)} requests</>} />
        <Kpi label="Avg TTFT (cache miss)" loading={loading} value={ms(k?.avg_ttft_miss_ms)}
          context={<>Cache hits <span className="num text-ink-2">{ms(k?.avg_ttft_hit_ms)}</span> · {int(k?.failovers)} failover{k?.failovers === 1 ? '' : 's'}</>} />
      </div>

      <div className="flex flex-wrap items-center gap-x-6 gap-y-3 rounded-lg border border-line bg-panel px-5 py-3.5">
        <span className="text-[12px] text-ink-3">Circuit breakers</span>
        {loading ? <Skeleton className="h-5 w-64" /> : data?.providers.map((p) => (
          <CircuitPill key={p.provider + p.priority_order} provider={p.provider} state={p.circuit_state}
            primary={p === primary} enabled={p.enabled} live={p.live} />
        ))}
        {data && (
          <span className="ml-auto inline-flex items-center gap-2 text-[12px] text-ink-3">
            <ShieldCheck className="size-4 text-good" aria-hidden />
            RLS: <span className="num text-ink-2">{int(data.isolation.other_tenant_rows_in_database)}</span> foreign ledger rows in DB,
            <span className="num text-ink">{int(data.isolation.other_tenant_rows_visible)}</span> visible
          </span>
        )}
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        <ErrorBoundary label="Token chart">
          <Panel title="Token consumption">
            {loading ? <Skeleton className="h-64" /> : data && data.kpis.requests > 0 ? <TokenChart series={data.series} range={range} />
              : <EmptyState title="No traffic in this range" hint="Send a prompt from the Playground or call /v1/chat/completions." />}
          </Panel>
        </ErrorBoundary>
        <ErrorBoundary label="Burn chart">
          <Panel title="Cost burn rate (USD)" action={k && <span className="text-[12px] text-ink-3 num">{usd8(k.spend_usd)} in {range}</span>}>
            {loading ? <Skeleton className="h-56" /> : data && data.kpis.requests > 0 ? <BurnChart series={data.series} range={range} />
              : <EmptyState title="Nothing billed yet" />}
          </Panel>
        </ErrorBoundary>
      </div>

      <Panel title="Recent requests" pad={false} action={k && <span className="text-[12px] text-ink-3 num">{tokens(k.prompt_tokens + k.completion_tokens)} tokens in {range}</span>}>
        {loading ? (
          <div className="p-5 space-y-2">{Array.from({ length: 5 }, (_, i) => <Skeleton key={i} className="h-8" />)}</div>
        ) : !data?.recent_requests.length ? (
          <EmptyState title="No requests yet" />
        ) : (
          <RequestsTable rows={data.recent_requests} />
        )}
      </Panel>
    </div>
  )
}

function RequestsTable({ rows }: { rows: RequestRow[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-[13px]">
        <thead>
          <tr className="text-left text-[12px] text-ink-3 border-b border-line">
            <th className="font-normal px-5 h-9">Request</th>
            <th className="font-normal px-3">Route</th>
            <th className="font-normal px-3">Cache</th>
            <th className="font-normal px-3 text-right">Tokens in / out</th>
            <th className="font-normal px-3 text-right">Cost</th>
            <th className="font-normal px-3 text-right">TTFT</th>
            <th className="font-normal px-5 text-right">When</th>
          </tr>
        </thead>
        <tbody className="font-mono num">
          {rows.map((r) => (
            <tr key={r.request_id} className="border-b border-line last:border-0 hover:bg-panel-2">
              <td className="px-5 h-10 text-ink-2 whitespace-nowrap">{r.request_id.slice(0, 18)}…</td>
              <td className="px-3 whitespace-nowrap font-sans">
                <span className="text-ink">{r.provider_used}</span> <span className="text-ink-3">{r.model_used}</span>
                {r.failover_triggered && <span className="ml-2"><Badge tone="warn">failover</Badge></span>}
                {r.status_code >= 400 && <span className="ml-2"><Badge tone="bad">{r.status_code}</Badge></span>}
              </td>
              <td className="px-3"><Badge tone={r.cache_status === 'HIT' ? 'good' : 'neutral'}>{r.cache_status}</Badge></td>
              <td className="px-3 text-right text-ink-2 whitespace-nowrap">{int(r.prompt_tokens)} / {int(r.completion_tokens)}</td>
              <td className="px-3 text-right whitespace-nowrap">{r.cache_status === 'HIT' ? <span className="text-good">saved {usd8(r.saved_cost_usd)}</span> : usd8(r.total_cost_usd)}</td>
              <td className="px-3 text-right text-ink-2">{ms(r.ttft_ms)}</td>
              <td className="px-5 text-right text-ink-3 font-sans whitespace-nowrap">{relative(r.created_at)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
