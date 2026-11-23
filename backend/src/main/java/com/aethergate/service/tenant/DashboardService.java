package com.aethergate.service.tenant;

import com.aethergate.config.TenantContext;
import com.aethergate.domain.model.EntryType;
import com.aethergate.domain.model.ProviderType;
import com.aethergate.service.llm.LlmRouterService;
import com.aethergate.service.tenant.AdminViews.ProviderView;
import com.aethergate.service.tenant.DashboardViews.IsolationProof;
import com.aethergate.service.tenant.DashboardViews.LedgerPage;
import com.aethergate.service.tenant.DashboardViews.Overview;
import com.aethergate.service.tenant.DashboardViews.ProviderStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Assembles console views from tenant-scoped queries plus two privileged facts (breakers, RLS proof). */
@Service
public class DashboardService {

    public enum Range {
        H24("24h", Duration.ofHours(24), "1 hour", ChronoUnit.HOURS),
        D7("7d", Duration.ofDays(7), "6 hours", ChronoUnit.HOURS),
        D30("30d", Duration.ofDays(30), "1 day", ChronoUnit.DAYS);

        final String label;
        final Duration window;
        final String stride;
        final ChronoUnit truncation;

        Range(String label, Duration window, String stride, ChronoUnit truncation) {
            this.label = label;
            this.window = window;
            this.stride = stride;
            this.truncation = truncation;
        }

        public static Range parse(String value) {
            for (Range r : values()) {
                if (r.label.equalsIgnoreCase(value)) {
                    return r;
                }
            }
            throw new IllegalArgumentException("range must be one of 24h, 7d, 30d");
        }
    }

    private final DashboardQueries queries;
    private final TenantAdminService admin;
    private final LlmRouterService router;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownerReadTx;
    private final Clock clock;

    public DashboardService(DashboardQueries queries, TenantAdminService admin, LlmRouterService router, JdbcTemplate jdbc,
                            PlatformTransactionManager txManager, Clock clock) {
        this.queries = queries;
        this.admin = admin;
        this.router = router;
        this.jdbc = jdbc;
        this.ownerReadTx = new TransactionTemplate(txManager);
        this.ownerReadTx.setReadOnly(true);
        this.clock = clock;
    }

    public Overview overview(TenantRef tenant, Range range) {
        Instant now = clock.instant();
        Instant since = now.minus(range.window).truncatedTo(range.truncation);
        Instant monthStart = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return TenantContext.callAs(tenant.id(), () -> {
            List<ProviderView> providers = admin.listProviders();
            Map<ProviderType, String> states = router.breakerStates(tenant.id(),
                    providers.stream().map(p -> ProviderType.valueOf(p.provider())).toList());
            List<ProviderStatus> statuses = providers.stream()
                    .map(p -> new ProviderStatus(p.provider(), p.modelName(), p.priorityOrder(), p.enabled(), p.live(),
                            states.get(ProviderType.valueOf(p.provider()))))
                    .toList();
            IsolationProof proof = new IsolationProof(foreignLedgerRowsInDatabase(tenant), queries.visibleForeignLedgerRows());
            return new Overview(tenant, range.label, queries.kpis(since, monthStart), queries.series(since, now, range.stride),
                    statuses, proof, queries.recentRequests(12));
        });
    }

    public LedgerPage ledger(TenantRef tenant, String type, int page, int size) {
        EntryType entryType = type == null || type.isBlank() || "ALL".equalsIgnoreCase(type) ? null : EntryType.valueOf(type.toUpperCase());
        int safeSize = Math.clamp(size, 1, 200);
        return TenantContext.callAs(tenant.id(), () -> queries.ledgerPage(entryType, Math.max(0, page), safeSize));
    }

    /** Owner-level count (outside RLS): how many foreign rows exist that RLS is hiding. */
    private long foreignLedgerRowsInDatabase(TenantRef tenant) {
        Long n = ownerReadTx.execute(s -> jdbc.queryForObject(
                "select count(*) from ledger_entries where tenant_id <> ?", Long.class, tenant.id()));
        return n == null ? 0 : n;
    }
}
