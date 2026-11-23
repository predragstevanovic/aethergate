package com.aethergate.service.tenant;

import com.aethergate.config.TenantContext;
import com.aethergate.config.TenantScoped;
import com.aethergate.domain.model.EntryType;
import com.aethergate.domain.model.LedgerEntry;
import com.aethergate.domain.model.Tenant;
import com.aethergate.domain.repository.LedgerEntryRepository;
import com.aethergate.domain.repository.TenantRepository;
import com.aethergate.service.tenant.DashboardViews.Kpis;
import com.aethergate.service.tenant.DashboardViews.LedgerPage;
import com.aethergate.service.tenant.DashboardViews.LedgerRow;
import com.aethergate.service.tenant.DashboardViews.RequestRow;
import com.aethergate.service.tenant.DashboardViews.SeriesPoint;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only console queries. Class-level {@code @TenantScoped}: every method runs under RLS,
 * the explicit {@code tenant_id = ?} predicates are only there so the planner uses the indexes.
 */
@Component
@TenantScoped(readOnly = true)
public class DashboardQueries {

    private final JdbcTemplate jdbc;
    private final TenantRepository tenants;
    private final LedgerEntryRepository ledger;

    public DashboardQueries(JdbcTemplate jdbc, TenantRepository tenants, LedgerEntryRepository ledger) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.ledger = ledger;
    }

    public Kpis kpis(Instant since, Instant monthStart) {
        UUID tenantId = TenantContext.require();
        Tenant t = tenants.findById(tenantId).orElseThrow(() -> new IllegalStateException("Tenant not visible"));
        BigDecimal mtd = ledger.sumSince(tenantId, EntryType.SETTLE, monthStart);
        return jdbc.queryForObject("""
                        select count(*)                                              as requests,
                               count(*) filter (where cache_status = 'HIT')          as hits,
                               count(*) filter (where failover_triggered)            as failovers,
                               coalesce(sum(prompt_tokens), 0)                       as prompt_tokens,
                               coalesce(sum(completion_tokens), 0)                   as completion_tokens,
                               coalesce(sum(total_cost_usd), 0)                      as spend,
                               coalesce(sum(saved_cost_usd), 0)                      as saved,
                               round(avg(ttft_ms) filter (where cache_status = 'MISS')) as ttft_miss,
                               round(avg(ttft_ms) filter (where cache_status = 'HIT'))  as ttft_hit
                        from llm_request_logs
                        where tenant_id = ? and created_at >= ?
                        """,
                (rs, i) -> {
                    long requests = rs.getLong("requests");
                    long hits = rs.getLong("hits");
                    return new Kpis(t.getAvailableBalanceUsd(), t.getReservedBalanceUsd(), t.getHardBudgetLimitUsd(), mtd,
                            rs.getBigDecimal("spend"), rs.getBigDecimal("saved"), requests, hits,
                            requests == 0 ? 0 : (double) hits / requests, rs.getLong("failovers"),
                            rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"),
                            nullableInt(rs, "ttft_miss"), nullableInt(rs, "ttft_hit"));
                },
                tenantId, Timestamp.from(since));
    }

    public List<SeriesPoint> series(Instant since, Instant until, String stride) {
        return jdbc.query("""
                        with buckets as (
                            select generate_series(date_bin(cast(? as interval), cast(? as timestamptz), timestamptz '2000-01-01'),
                                                   cast(? as timestamptz), cast(? as interval)) as bucket
                        )
                        select b.bucket,
                               coalesce(sum(l.prompt_tokens), 0)     as prompt_tokens,
                               coalesce(sum(l.completion_tokens), 0) as completion_tokens,
                               coalesce(sum(l.total_cost_usd), 0)    as cost,
                               coalesce(sum(l.saved_cost_usd), 0)    as saved,
                               count(l.id)                           as requests,
                               count(l.id) filter (where l.cache_status = 'HIT') as hits
                        from buckets b
                        left join llm_request_logs l
                               on l.tenant_id = ? and l.created_at >= b.bucket and l.created_at < b.bucket + cast(? as interval)
                        group by b.bucket
                        order by b.bucket
                        """,
                (rs, i) -> new SeriesPoint(rs.getTimestamp("bucket").toInstant(), rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens"), rs.getBigDecimal("cost"), rs.getBigDecimal("saved"),
                        rs.getLong("requests"), rs.getLong("hits")),
                stride, Timestamp.from(since), Timestamp.from(until), stride, TenantContext.require(), stride);
    }

    public List<RequestRow> recentRequests(int limit) {
        return jdbc.query("""
                        select request_id, provider_used, model_used, prompt_tokens, completion_tokens, total_cost_usd,
                               saved_cost_usd, latency_ms, ttft_ms, cache_status, failover_triggered, status_code, created_at
                        from llm_request_logs where tenant_id = ? order by created_at desc limit ?
                        """,
                (rs, i) -> new RequestRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                        rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getInt(8), rs.getInt(9), rs.getString(10),
                        rs.getBoolean(11), rs.getInt(12), rs.getTimestamp(13).toInstant()),
                TenantContext.require(), limit);
    }

    /** Rows of other tenants this session can see. Under RLS this is always 0. */
    public long visibleForeignLedgerRows() {
        Long n = jdbc.queryForObject("select count(*) from ledger_entries where tenant_id <> ?", Long.class, TenantContext.require());
        return n == null ? 0 : n;
    }

    public LedgerPage ledgerPage(EntryType type, int page, int size) {
        UUID tenantId = TenantContext.require();
        PageRequest pageable = PageRequest.of(page, size);
        Page<LedgerEntry> result = type == null
                ? ledger.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable)
                : ledger.findByTenantIdAndEntryTypeOrderByCreatedAtDesc(tenantId, type, pageable);

        Map<EntryType, BigDecimal> totals = new EnumMap<>(EntryType.class);
        for (EntryType t : EntryType.values()) {
            totals.put(t, BigDecimal.ZERO);
        }
        jdbc.query("select entry_type, sum(amount_usd) from ledger_entries where tenant_id = ? group by entry_type",
                (ResultSet rs) -> {
                    totals.put(EntryType.valueOf(rs.getString(1)), rs.getBigDecimal(2));
                }, tenantId);
        Tenant t = tenants.findById(tenantId).orElseThrow(() -> new IllegalStateException("Tenant not visible"));

        // Invariants: credits − settled = available + reserved, and every hold is either open or cleared.
        boolean fundsBalance = totals.get(EntryType.TOP_UP).subtract(totals.get(EntryType.SETTLE))
                .compareTo(t.getAvailableBalanceUsd().add(t.getReservedBalanceUsd())) == 0;
        boolean holdsBalance = totals.get(EntryType.RESERVE)
                .compareTo(totals.get(EntryType.SETTLE).add(totals.get(EntryType.RELEASE)).add(t.getReservedBalanceUsd())) == 0;

        Map<String, BigDecimal> totalsByName = new LinkedHashMap<>();
        totals.forEach((k, v) -> totalsByName.put(k.name(), v));
        List<LedgerRow> rows = result.getContent().stream()
                .map(e -> new LedgerRow(e.getId(), e.getRequestId(), e.getEntryType().name(), e.getAmountUsd(),
                        e.getBalanceAfterUsd(), e.getDescription(), e.getCreatedAt()))
                .toList();
        return new LedgerPage(rows, page, size, result.getTotalElements(), result.getTotalPages(), totalsByName,
                fundsBalance && holdsBalance);
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        BigDecimal v = rs.getBigDecimal(column);
        return v == null ? null : v.intValue();
    }
}
