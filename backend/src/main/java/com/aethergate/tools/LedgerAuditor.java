package com.aethergate.tools;

import com.aethergate.config.TenantAspect;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * QA preview and invariant audit of a live database. Prints what a reviewer would want to see
 * (tenants, balances, traffic, cache) and fails (exit 1) if any financial or security
 * invariant is violated. Safe to run against production: read-only except for one UPDATE that
 * is expected to be rejected inside a rolled-back transaction.
 */
@Component
public class LedgerAuditor {

    public record Check(String name, boolean passed, String detail) {}

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;

    public LedgerAuditor(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.txManager = txManager;
    }

    public List<Check> audit() {
        List<Check> checks = new ArrayList<>();

        List<Map<String, Object>> funds = jdbc.queryForList("""
                select t.slug,
                       t.available_balance_usd + t.reserved_balance_usd as held,
                       coalesce(sum(e.amount_usd) filter (where e.entry_type = 'TOP_UP'), 0)
                     - coalesce(sum(e.amount_usd) filter (where e.entry_type = 'SETTLE'), 0) as expected
                from tenants t left join ledger_entries e on e.tenant_id = t.id
                group by t.id, t.slug, t.available_balance_usd, t.reserved_balance_usd
                having t.available_balance_usd + t.reserved_balance_usd
                    <> coalesce(sum(e.amount_usd) filter (where e.entry_type = 'TOP_UP'), 0)
                     - coalesce(sum(e.amount_usd) filter (where e.entry_type = 'SETTLE'), 0)""");
        checks.add(new Check("Funds equation: TOP_UP - SETTLE = available + reserved (every tenant)", funds.isEmpty(),
                funds.isEmpty() ? "all tenants balance" : funds.toString()));

        List<Map<String, Object>> holds = jdbc.queryForList("""
                select t.slug from tenants t left join ledger_entries e on e.tenant_id = t.id
                group by t.id, t.slug, t.reserved_balance_usd
                having coalesce(sum(e.amount_usd) filter (where e.entry_type = 'RESERVE'), 0)
                    <> coalesce(sum(e.amount_usd) filter (where e.entry_type in ('SETTLE', 'RELEASE')), 0) + t.reserved_balance_usd""");
        checks.add(new Check("Holds: RESERVE = SETTLE + RELEASE + open reservations", holds.isEmpty(),
                holds.isEmpty() ? "no leaked holds" : holds.toString()));

        Integer brokenRequests = jdbc.queryForObject("""
                select count(*) from (
                  select tenant_id, request_id from ledger_entries where entry_type in ('RESERVE','SETTLE','RELEASE')
                  group by tenant_id, request_id
                  having bool_or(entry_type in ('SETTLE','RELEASE')) and
                         coalesce(sum(amount_usd) filter (where entry_type = 'RESERVE'), 0)
                      <> coalesce(sum(amount_usd) filter (where entry_type in ('SETTLE','RELEASE')), 0)) x""", Integer.class);
        checks.add(new Check("Per request: cleared holds split exactly into SETTLE + RELEASE", brokenRequests == 0,
                brokenRequests + " mismatched requests"));

        Integer negative = jdbc.queryForObject(
                "select count(*) from tenants where available_balance_usd < 0 or reserved_balance_usd < 0", Integer.class);
        checks.add(new Check("No negative balances", negative == 0, negative + " tenants below zero"));

        Integer splitProjects = jdbc.queryForObject("""
                select count(*) from (
                  select tenant_id, request_id from ledger_entries where entry_type in ('RESERVE','SETTLE','RELEASE')
                  group by tenant_id, request_id
                  having count(distinct coalesce(project_id::text, '-')) > 1) x""", Integer.class);
        checks.add(new Check("Projects: RESERVE, SETTLE and RELEASE of a request hit the same project", splitProjects == 0,
                splitProjects + " requests split across projects"));

        Integer overBudget = jdbc.queryForObject("""
                select count(*) from projects p
                where p.monthly_budget_usd is not null
                  and (select coalesce(sum(e.amount_usd), 0) from ledger_entries e
                       where e.tenant_id = p.tenant_id and e.project_id = p.id and e.entry_type = 'SETTLE'
                         and e.created_at >= date_trunc('month', now() at time zone 'UTC') at time zone 'UTC')
                      > p.monthly_budget_usd""", Integer.class);
        checks.add(new Check("Projects: month-to-date spend within every project budget", overBudget == 0,
                overBudget + " projects over budget"));

        Integer afterArchive = jdbc.queryForObject("""
                select count(*) from ledger_entries e join projects p on p.tenant_id = e.tenant_id and p.id = e.project_id
                where p.archived_at is not null and e.entry_type = 'RESERVE' and e.created_at > p.archived_at""", Integer.class);
        checks.add(new Check("Projects: archived projects accept no new holds", afterArchive == 0,
                afterArchive + " holds after archive"));

        Integer costMismatch = jdbc.queryForObject("""
                select count(*) from llm_request_logs l
                join ledger_entries e on e.tenant_id = l.tenant_id and e.request_id = l.request_id and e.entry_type = 'SETTLE'
                where l.cache_status = 'MISS' and l.total_cost_usd <> e.amount_usd""", Integer.class);
        checks.add(new Check("Request logs agree with SETTLE amounts", costMismatch == 0, costMismatch + " mismatches"));

        Integer hitLeak = jdbc.queryForObject("""
                select count(*) from llm_request_logs l
                where l.cache_status = 'HIT' and (l.total_cost_usd <> 0 or exists (
                    select 1 from ledger_entries e where e.tenant_id = l.tenant_id and e.request_id = l.request_id))""", Integer.class);
        checks.add(new Check("Cache HITs cost $0 and never touch the ledger", hitLeak == 0, hitLeak + " violations"));

        checks.add(rlsCheck());
        checks.add(immutabilityCheck());

        Integer badVectors = jdbc.queryForObject("""
                select count(*) from semantic_cache
                where extensions.vector_dims(embedding) <> 384 or abs(extensions.vector_norm(embedding) - 1) > 0.001""", Integer.class);
        checks.add(new Check("Semantic cache: 384-dim unit vectors", badVectors == 0, badVectors + " malformed embeddings"));

        Integer rawKeys = jdbc.queryForObject(
                "select count(*) from api_keys where key_hash !~ '^[0-9a-f]{64}$' or key_hash like 'ag_live%'", Integer.class);
        checks.add(new Check("API keys stored only as SHA-256 hashes", rawKeys == 0, rawKeys + " suspicious key rows"));
        return checks;
    }

    /** As the RLS-bound app role for tenant A, count rows of every other tenant in each tenant-owned table. */
    private Check rlsCheck() {
        List<UUID> tenants = jdbc.queryForList("select id from tenants order by created_at limit 2", UUID.class);
        if (tenants.size() < 2) {
            return new Check("RLS isolation across all tenant tables", true, "fewer than 2 tenants, nothing to compare");
        }
        UUID a = tenants.getFirst();
        long foreignInDb = jdbc.queryForObject("select count(*) from ledger_entries where tenant_id <> ?", Long.class, a);
        Long visible = new TransactionTemplate(txManager).execute(s -> {
            jdbc.queryForObject("select set_config('app.current_tenant_id', ?, true)", String.class, a.toString());
            jdbc.execute("set local role " + TenantAspect.APP_ROLE);
            long total = 0;
            for (String table : List.of("ledger_entries", "llm_request_logs", "semantic_cache", "api_keys", "provider_configs",
                    "rate_limit_windows", "projects", "guardrail_rules", "guardrail_events")) {
                total += jdbc.queryForObject("select count(*) from " + table + " where tenant_id <> ?", Long.class, a);
            }
            total += jdbc.queryForObject("select count(*) from tenants where id <> ?", Long.class, a);
            return total;
        });
        return new Check("RLS isolation across all tenant tables", visible != null && visible == 0,
                foreignInDb + " foreign ledger rows exist, " + visible + " visible to tenant session");
    }

    private Check immutabilityCheck() {
        try {
            new TransactionTemplate(txManager).executeWithoutResult(s -> {
                jdbc.update("update ledger_entries set amount_usd = amount_usd where id = (select id from ledger_entries limit 1)");
                s.setRollbackOnly();
            });
            return new Check("Ledger is append-only (UPDATE rejected by trigger)", false, "UPDATE was accepted!");
        } catch (RuntimeException e) {
            boolean ok = String.valueOf(e.getMessage()).contains("append-only")
                    || (e.getCause() != null && String.valueOf(e.getCause().getMessage()).contains("append-only"));
            return new Check("Ledger is append-only (UPDATE rejected by trigger)", ok, ok ? "trigger fired" : e.getMessage());
        }
    }

    /** Human-readable snapshot printed before the checks. */
    public List<String> preview() {
        List<String> out = new ArrayList<>();
        out.add(String.format("%-18s %12s %10s %8s %7s %12s %12s %9s", "TENANT", "AVAILABLE", "RESERVED", "REQUESTS",
                "HIT %", "SPEND 30d", "SAVED 30d", "TTFT ms"));
        jdbc.query("""
                select t.name, t.available_balance_usd, t.reserved_balance_usd,
                       count(l.id) as requests,
                       coalesce(round(100.0 * count(l.id) filter (where l.cache_status = 'HIT') / nullif(count(l.id), 0), 1), 0) as hit_pct,
                       coalesce(sum(l.total_cost_usd), 0) as spend, coalesce(sum(l.saved_cost_usd), 0) as saved,
                       coalesce(round(avg(l.ttft_ms) filter (where l.cache_status = 'MISS')), 0) as ttft
                from tenants t left join llm_request_logs l on l.tenant_id = t.id and l.created_at > now() - interval '30 days'
                where t.id in (?, ?, ?)
                group by t.id order by t.name""", rs -> {
            out.add(String.format("%-18s %12s %10s %8d %6s%% %12s %12s %9d", rs.getString(1), "$" + rs.getBigDecimal(2).setScale(4, java.math.RoundingMode.HALF_UP),
                    "$" + rs.getBigDecimal(3).setScale(4, java.math.RoundingMode.HALF_UP), rs.getLong(4), rs.getBigDecimal(5),
                    "$" + rs.getBigDecimal(6).setScale(4, java.math.RoundingMode.HALF_UP),
                    "$" + rs.getBigDecimal(7).setScale(4, java.math.RoundingMode.HALF_UP), rs.getLong(8)));
        }, DemoDataSeeder.SEEDED.toArray());
        out.add("");
        out.add("Latest ledger lines (Vortex AI Labs):");
        jdbc.query("""
                select to_char(created_at, 'MM-DD HH24:MI:SS'), entry_type, amount_usd, balance_after_usd, request_id
                from ledger_entries where tenant_id = ? order by created_at desc, entry_type limit 6""", rs -> {
            out.add(String.format("  %s  %-8s %14s  -> %14s  %s", rs.getString(1), rs.getString(2), "$" + rs.getBigDecimal(3).toPlainString(),
                    "$" + rs.getBigDecimal(4).toPlainString(), rs.getString(5)));
        }, DemoDataSeeder.VORTEX);
        out.add("");
        out.add("Hottest cached prompts:");
        jdbc.query("""
                select t.name, c.hit_count, c.prompt_text from semantic_cache c join tenants t on t.id = c.tenant_id
                order by c.hit_count desc limit 5""", rs -> {
            out.add(String.format("  %-16s %3d hits  %s", rs.getString(1), rs.getInt(2), rs.getString(3)));
        });
        BigDecimal total = jdbc.queryForObject("select coalesce(sum(amount_usd), 0) from ledger_entries", BigDecimal.class);
        Integer lines = jdbc.queryForObject("select count(*) from ledger_entries", Integer.class);
        out.add("");
        out.add("Ledger: " + lines + " lines, gross movement $" + total.toPlainString());
        return out;
    }
}
