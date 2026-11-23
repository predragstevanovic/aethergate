package com.aethergate.service.project;

import com.aethergate.service.StaleVersionException;
import com.aethergate.service.ResourceConflictException;
import com.aethergate.config.TenantContext;
import com.aethergate.config.TenantScoped;
import com.aethergate.domain.model.ApiKey;
import com.aethergate.domain.model.Project;
import com.aethergate.domain.model.ProjectStatus;
import com.aethergate.domain.repository.ApiKeyRepository;
import com.aethergate.domain.repository.ProjectRepository;
import com.aethergate.service.metering.CostCalculator;
import com.aethergate.service.project.ProjectViews.ArchiveResult;
import com.aethergate.service.project.ProjectViews.DailyUsage;
import com.aethergate.service.project.ProjectViews.KeyRef;
import com.aethergate.service.project.ProjectViews.ProjectDetail;
import com.aethergate.service.project.ProjectViews.ProjectDraft;
import com.aethergate.service.project.ProjectViews.ProjectList;
import com.aethergate.service.project.ProjectViews.ProjectView;
import com.aethergate.service.project.ProjectViews.UnassignedUsage;
import com.aethergate.service.ratelimit.RateLimiterService;
import com.aethergate.service.tenant.TenantDirectoryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Projects (cost centres) of the current tenant: CRUD plus the rules that make it safe for money.
 *
 * <ul>
 *   <li>Names are unique among ACTIVE projects (case-insensitive), enforced in code and by a partial unique index.</li>
 *   <li>Edits are optimistic: the caller sends the version it read (HTTP If-Match); a stale version is rejected.</li>
 *   <li>Archiving revokes the project's keys in the same transaction; restoring never resurrects keys.</li>
 *   <li>A project that ever appeared in the ledger or request logs cannot be deleted, only archived.</li>
 *   <li>A budget can never be set below the month's committed spend, so "spend &lt;= budget" always holds.</li>
 * </ul>
 * Everything runs under PostgreSQL RLS, so another tenant's project is simply "not found".
 */
@Service
public class ProjectService {

    public static final int MAX_ACTIVE_PROJECTS = 25;
    static final BigDecimal MAX_BUDGET = new BigDecimal("1000000");

    private final ProjectRepository projects;
    private final ApiKeyRepository apiKeys;
    private final JdbcTemplate jdbc;
    private final TenantDirectoryService directory;
    private final RateLimiterService rateLimiter;
    private final Clock clock;

    public ProjectService(ProjectRepository projects, ApiKeyRepository apiKeys, JdbcTemplate jdbc,
                          TenantDirectoryService directory, RateLimiterService rateLimiter, Clock clock) {
        this.projects = projects;
        this.apiKeys = apiKeys;
        this.jdbc = jdbc;
        this.directory = directory;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    // ── queries ──────────────────────────────────────────────────────────────────────────

    @TenantScoped(readOnly = true)
    public ProjectList list() {
        Instant monthStart = monthStart();
        Instant since30d = clock.instant().minus(Duration.ofDays(30));
        List<ProjectView> all = jdbc.query(PROJECT_SQL + " order by p.status, lower(p.name)",
                (rs, i) -> toView(rs), ts(monthStart), ts(since30d));
        UnassignedUsage unassigned = jdbc.queryForObject("""
                select (select coalesce(sum(amount_usd), 0) from ledger_entries
                         where tenant_id = aethergate.current_tenant_id() and project_id is null
                           and entry_type = 'SETTLE' and created_at >= ?)                    as mtd,
                       count(*)                                                              as requests,
                       coalesce(sum(total_cost_usd), 0)                                      as cost
                from llm_request_logs
                where tenant_id = aethergate.current_tenant_id() and project_id is null and created_at >= ?
                """, (rs, i) -> new UnassignedUsage(money(rs, "mtd"), rs.getLong("requests"), money(rs, "cost")),
                ts(monthStart), ts(since30d));
        return new ProjectList(all, unassigned, MAX_ACTIVE_PROJECTS);
    }

    @TenantScoped(readOnly = true)
    public ProjectDetail get(UUID id) {
        ProjectView view = view(id);
        Instant since30d = clock.instant().minus(Duration.ofDays(30));
        List<DailyUsage> daily = jdbc.query("""
                select (created_at at time zone 'UTC')::date as day, count(*) as requests,
                       count(*) filter (where cache_status = 'HIT') as hits, coalesce(sum(total_cost_usd), 0) as cost
                from llm_request_logs
                where tenant_id = aethergate.current_tenant_id() and project_id = ? and created_at >= ?
                group by 1 order by 1
                """, (rs, i) -> new DailyUsage(rs.getObject("day", LocalDate.class), rs.getLong("requests"),
                rs.getLong("hits"), money(rs, "cost")), id, ts(since30d));
        List<KeyRef> keys = apiKeys.findAllByOrderByCreatedAtDesc().stream()
                .filter(k -> id.equals(k.getProjectId()))
                .map(k -> new KeyRef(k.getId(), k.getName(), k.getKeyPrefix(), k.isActive()))
                .toList();
        return new ProjectDetail(view, daily, keys);
    }

    /** For callers that attach traffic to a project (playground, key assignment): it must exist and be active. */
    @TenantScoped(readOnly = true)
    public void ensureActive(UUID id) {
        Project p = find(id);
        if (!p.isActive()) {
            throw new ProjectArchivedException(p.getName());
        }
    }

    // ── commands ─────────────────────────────────────────────────────────────────────────

    @TenantScoped
    public ProjectView create(ProjectDraft draft) {
        ProjectDraft d = normalise(draft);
        if (projects.countByStatus(ProjectStatus.ACTIVE) >= MAX_ACTIVE_PROJECTS) {
            throw new ResourceConflictException("A workspace can have at most " + MAX_ACTIVE_PROJECTS
                    + " active projects; archive one first");
        }
        if (projects.existsByStatusAndNameIgnoreCase(ProjectStatus.ACTIVE, d.name())) {
            throw duplicate(d.name());
        }
        Project p = projects.saveAndFlush(new Project(TenantContext.require(), d.name(), d.description(), d.monthlyBudgetUsd()));
        return view(p.getId());
    }

    /** Full replacement of name, description and budget, guarded by the version the client read. */
    @TenantScoped
    public ProjectView update(UUID id, long expectedVersion, ProjectDraft draft) {
        ProjectDraft d = normalise(draft);
        Project p = find(id);
        if (p.getVersion() != expectedVersion) {
            throw new StaleVersionException(expectedVersion, p.getVersion());
        }
        if (!p.isActive()) {
            throw new ProjectArchivedException(p.getName());
        }
        if (projects.existsByStatusAndNameIgnoreCaseAndIdNot(ProjectStatus.ACTIVE, d.name(), id)) {
            throw duplicate(d.name());
        }
        BigDecimal committed = view(id).committedUsd();
        if (d.monthlyBudgetUsd() != null && d.monthlyBudgetUsd().compareTo(committed) < 0) {
            // keeps the invariant "month-to-date spend <= budget" true; archive the project to stop it instead
            throw new ResourceConflictException("Budget $" + d.monthlyBudgetUsd().stripTrailingZeros().toPlainString()
                    + " is below this month's committed spend of $" + committed.toPlainString()
                    + "; choose a higher budget or archive the project");
        }
        p.rename(d.name());
        p.describe(d.description());
        p.setMonthlyBudget(d.monthlyBudgetUsd());
        projects.saveAndFlush(p); // bumps @Version now, so the response carries the new ETag
        return view(id);
    }

    @TenantScoped
    public ArchiveResult archive(UUID id) {
        Project p = find(id);
        List<ApiKey> keys = p.isActive() ? apiKeys.findAllByProjectIdAndActiveTrue(id) : List.of();
        p.archive();
        keys.forEach(ApiKey::revoke);
        projects.saveAndFlush(p);
        afterCommit(() -> keys.forEach(this::evict));
        return new ArchiveResult(view(id), keys.size());
    }

    @TenantScoped
    public ProjectView restore(UUID id) {
        Project p = find(id);
        if (!p.isActive() && projects.existsByStatusAndNameIgnoreCase(ProjectStatus.ACTIVE, p.getName())) {
            throw new ResourceConflictException("An active project is already called '" + p.getName() + "'; rename it first");
        }
        if (!p.isActive() && projects.countByStatus(ProjectStatus.ACTIVE) >= MAX_ACTIVE_PROJECTS) {
            throw new ResourceConflictException("A workspace can have at most " + MAX_ACTIVE_PROJECTS + " active projects");
        }
        p.restore();
        projects.saveAndFlush(p);
        return view(id);
    }

    /** Hard delete is only for mistakes: a project with any metering history must be archived instead. */
    @TenantScoped
    public void delete(UUID id) {
        Project p = find(id);
        Boolean used = jdbc.queryForObject("""
                select exists (select 1 from ledger_entries where tenant_id = aethergate.current_tenant_id() and project_id = ?)
                    or exists (select 1 from llm_request_logs where tenant_id = aethergate.current_tenant_id() and project_id = ?)
                """, Boolean.class, id, id);
        if (Boolean.TRUE.equals(used)) {
            throw new ResourceConflictException("Project '" + p.getName()
                    + "' has billing history and the ledger is append-only; archive it instead");
        }
        List<ApiKey> keys = apiKeys.findAllByOrderByCreatedAtDesc().stream().filter(k -> id.equals(k.getProjectId())).toList();
        keys.forEach(k -> k.assignProject(null));
        apiKeys.flush();
        projects.delete(p);
        projects.flush();
        afterCommit(() -> keys.forEach(this::evict));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private Project find(UUID id) {
        return projects.findById(id).orElseThrow(() -> new NoSuchElementException("Project not found"));
    }

    private ProjectView view(UUID id) {
        List<ProjectView> rows = jdbc.query(PROJECT_SQL + " and p.id = ?", (rs, i) -> toView(rs),
                ts(monthStart()), ts(clock.instant().minus(Duration.ofDays(30))), id);
        if (rows.isEmpty()) {
            throw new NoSuchElementException("Project not found");
        }
        return rows.getFirst();
    }

    static ProjectDraft normalise(ProjectDraft draft) {
        String name = draft.name() == null ? "" : draft.name().strip();
        if (name.length() < 2 || name.length() > 80) {
            throw new IllegalArgumentException("Project name must be 2-80 characters");
        }
        String description = draft.description() == null || draft.description().isBlank() ? null : draft.description().strip();
        if (description != null && description.length() > 280) {
            throw new IllegalArgumentException("Description must be at most 280 characters");
        }
        BigDecimal budget = draft.monthlyBudgetUsd();
        if (budget != null) {
            if (budget.signum() <= 0 || budget.compareTo(MAX_BUDGET) > 0) {
                throw new IllegalArgumentException("Monthly budget must be between $0 and $1,000,000 (or empty for no cap)");
            }
            budget = CostCalculator.normalize(budget);
        }
        return new ProjectDraft(name, description, budget);
    }

    private static ResourceConflictException duplicate(String name) {
        return new ResourceConflictException("An active project called '" + name + "' already exists");
    }

    private void evict(ApiKey key) {
        directory.evictKey(key.getKeyHash());
        rateLimiter.invalidate(key.getId());
    }

    /** Cache eviction must follow the commit, or a concurrent request could re-cache the old key state. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private Instant monthStart() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    private static BigDecimal money(ResultSet rs, String col) throws SQLException {
        return CostCalculator.normalize(rs.getBigDecimal(col));
    }

    private static ProjectView toView(ResultSet rs) throws SQLException {
        BigDecimal budget = rs.getBigDecimal("monthly_budget_usd");
        BigDecimal mtd = money(rs, "mtd");
        BigDecimal holds = money(rs, "holds");
        BigDecimal committed = mtd.add(holds);
        BigDecimal remaining = budget == null ? null : CostCalculator.normalize(budget.subtract(committed).max(BigDecimal.ZERO));
        Double utilization = budget == null ? null
                : committed.divide(budget, 4, RoundingMode.HALF_UP).doubleValue();
        Timestamp archived = rs.getTimestamp("archived_at");
        return new ProjectView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                rs.getString("status"), budget == null ? null : CostCalculator.normalize(budget), mtd, holds, committed,
                remaining, utilization, rs.getLong("requests"), rs.getLong("hits"), money(rs, "cost"),
                rs.getLong("active_keys"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), archived == null ? null : archived.toInstant());
    }

    /** One row per project with its budget position; parameters: month start, 30-day window start. */
    private static final String PROJECT_SQL = """
            select p.id, p.name, p.description, p.monthly_budget_usd, p.status, p.version,
                   p.created_at, p.updated_at, p.archived_at,
                   coalesce(s.mtd, 0) as mtd, coalesce(h.holds, 0) as holds,
                   coalesce(l.requests, 0) as requests, coalesce(l.hits, 0) as hits, coalesce(l.cost, 0) as cost,
                   coalesce(k.active_keys, 0) as active_keys
            from projects p
            left join lateral (select sum(e.amount_usd) as mtd from ledger_entries e
                               where e.tenant_id = p.tenant_id and e.project_id = p.id
                                 and e.entry_type = 'SETTLE' and e.created_at >= ?) s on true
            left join lateral (select sum(r.amount_usd) as holds from ledger_entries r
                               where r.tenant_id = p.tenant_id and r.project_id = p.id and r.entry_type = 'RESERVE'
                                 and not exists (select 1 from ledger_entries c
                                                 where c.tenant_id = r.tenant_id and c.request_id = r.request_id
                                                   and c.entry_type in ('SETTLE', 'RELEASE'))) h on true
            left join lateral (select count(*) as requests, count(*) filter (where g.cache_status = 'HIT') as hits,
                                      sum(g.total_cost_usd) as cost
                               from llm_request_logs g
                               where g.tenant_id = p.tenant_id and g.project_id = p.id and g.created_at >= ?) l on true
            left join lateral (select count(*) as active_keys from api_keys a
                               where a.tenant_id = p.tenant_id and a.project_id = p.id and a.is_active) k on true
            where p.tenant_id = aethergate.current_tenant_id()
            """;
}
