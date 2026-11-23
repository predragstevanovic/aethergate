import { ChevronLeft, ChevronRight, Plus } from 'lucide-react'
import { useSearchParams } from 'react-router'
import { Badge, Button, EmptyState, ErrorBanner, PageHeader, Panel, Segmented, Skeleton } from '../components/ui/primitives'
import { useAsyncAction } from '../hooks/useInteractions'
import { useResource } from '../hooks/useResource'
import { get, post, type EntryType, type LedgerPage } from '../lib/api'
import { dateTime, int, usd8 } from '../lib/format'

type Filter = 'ALL' | EntryType
const FILTERS: { value: Filter; label: string }[] = [
  { value: 'ALL', label: 'All' },
  { value: 'RESERVE', label: 'Reserve' },
  { value: 'SETTLE', label: 'Settle' },
  { value: 'RELEASE', label: 'Release' },
  { value: 'TOP_UP', label: 'Top-up' },
]

const TYPE_TONE: Record<EntryType, 'accent' | 'neutral' | 'good' | 'warn'> = {
  RESERVE: 'warn',
  SETTLE: 'accent',
  RELEASE: 'neutral',
  TOP_UP: 'good',
}

const SIGN: Record<EntryType, string> = { TOP_UP: '+', RESERVE: '−', SETTLE: '', RELEASE: '+' }
const PAGE_SIZE = 25

export function LedgerAuditPage() {
  const [params, setParams] = useSearchParams()
  const type = (FILTERS.some((f) => f.value === params.get('type')) ? params.get('type') : 'ALL') as Filter
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const { data, error, loading, refreshing, reload } = useResource(
    () => get<LedgerPage>(`/api/v1/dashboard/ledger?type=${type}&page=${page}&size=${PAGE_SIZE}`),
    [type, page],
  )
  const topUp = useAsyncAction(async () => {
    await post('/api/v1/tenant/top-up', { amount_usd: '50.00' })
    reload()
  })
  const go = (next: Partial<{ type: Filter; page: number }>) => {
    const t = next.type ?? type
    const p = next.page ?? (next.type ? 0 : page)
    setParams(p > 0 ? { type: t, page: String(p) } : { type: t })
  }
  const totals = data?.totals_by_type

  return (
    <div className="space-y-8">
      <PageHeader
        title="Immutable ledger"
        description="Append-only double-entry metering. Every request holds its worst-case cost (RESERVE), then charges the exact cost (SETTLE) and returns the rest (RELEASE). Updates and deletes are rejected by a database trigger."
        actions={
          <Button variant="primary" icon={<Plus className="size-4" />} loading={topUp.pending} onClick={() => void topUp.run()}>
            Top up $50.00
          </Button>
        }
      />
      {topUp.error && <ErrorBanner error={topUp.error} onRetry={() => void topUp.run()} />}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        {(['TOP_UP', 'RESERVE', 'SETTLE', 'RELEASE'] as EntryType[]).map((t) => (
          <div key={t} className="rounded-lg border border-line bg-panel p-4">
            <p className="text-[12px] text-ink-3">Σ {t.replace('_', '-').toLowerCase()}</p>
            {loading ? <Skeleton className="h-5 w-28 mt-2" /> : <p className="mt-1 font-mono text-[15px] num">{usd8(totals?.[t])}</p>}
          </div>
        ))}
        <div className="rounded-lg border border-line bg-panel p-4">
          <p className="text-[12px] text-ink-3">Invariants</p>
          {loading ? <Skeleton className="h-5 w-24 mt-2" /> : data?.balanced ? (
            <p className="mt-1.5"><Badge tone="good" dot>BALANCED</Badge></p>
          ) : (
            <p className="mt-1.5"><Badge tone="bad" dot>MISMATCH</Badge></p>
          )}
          <p className="mt-1.5 text-[11px] text-ink-3">top-ups − settled = available + reserved</p>
        </div>
      </div>

      {error && <ErrorBanner error={error} onRetry={reload} />}

      <Panel
        pad={false}
        title={<span className="flex items-center gap-3">Entries {data && <span className="text-ink-3 font-normal num">{int(data.total_elements)}</span>}{refreshing && <span className="text-[12px] text-ink-3 font-normal">loading…</span>}</span>}
        action={<Segmented label="Entry type" value={type} options={FILTERS} onChange={(t) => go({ type: t })} />}
      >
        {loading ? (
          <div className="p-5 space-y-2">{Array.from({ length: 8 }, (_, i) => <Skeleton key={i} className="h-8" />)}</div>
        ) : !data?.entries.length ? (
          <EmptyState title="No entries for this filter" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-[13px]">
              <thead>
                <tr className="text-left text-[12px] text-ink-3 border-b border-line">
                  <th className="font-normal px-5 h-9">Time (local)</th>
                  <th className="font-normal px-3">Type</th>
                  <th className="font-normal px-3">Request</th>
                  <th className="font-normal px-3 text-right">Amount</th>
                  <th className="font-normal px-3 text-right">Available after</th>
                  <th className="font-normal px-5">Description</th>
                </tr>
              </thead>
              <tbody className="font-mono num">
                {data.entries.map((e) => (
                  <tr key={e.id} className="border-b border-line last:border-0 hover:bg-panel-2">
                    <td className="px-5 h-10 text-ink-3 whitespace-nowrap">{dateTime(e.created_at)}</td>
                    <td className="px-3"><Badge tone={TYPE_TONE[e.entry_type]}>{e.entry_type}</Badge></td>
                    <td className="px-3 text-ink-2 whitespace-nowrap">{e.request_id}</td>
                    <td className="px-3 text-right whitespace-nowrap">{SIGN[e.entry_type]}{usd8(e.amount_usd)}</td>
                    <td className="px-3 text-right text-ink-2 whitespace-nowrap">{usd8(e.balance_after_usd)}</td>
                    <td className="px-5 text-ink-3 font-sans truncate max-w-80" title={e.description ?? ''}>{e.description}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data && data.total_pages > 1 && (
          <footer className="flex items-center justify-between border-t border-line px-5 h-12 text-[12px] text-ink-3">
            <span className="num">Page {page + 1} of {int(data.total_pages)}</span>
            <div className="flex gap-1">
              <Button size="sm" variant="ghost" disabled={page === 0} onClick={() => go({ page: page - 1 })} aria-label="Previous page" icon={<ChevronLeft className="size-4" />} />
              <Button size="sm" variant="ghost" disabled={page + 1 >= data.total_pages} onClick={() => go({ page: page + 1 })} aria-label="Next page" icon={<ChevronRight className="size-4" />} />
            </div>
          </footer>
        )}
      </Panel>
    </div>
  )
}
