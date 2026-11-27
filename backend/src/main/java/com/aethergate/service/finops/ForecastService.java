package com.aethergate.service.finops;

import com.aethergate.config.TenantScoped;
import com.aethergate.service.finops.FinOpsViews.Forecast;
import com.aethergate.service.finops.FinOpsViews.ForecastPoint;
import com.aethergate.service.finops.FinOpsViews.ProjectForecast;
import com.aethergate.service.metering.CostCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Month-end spend forecast from the ledger (settled spend is the source of truth for money).
 *
 * <p>Model: month-to-date + trailing-7-day daily run rate x remaining days. The band is +/- 1.28 sigma (80 %) of the
 * last 14 days' daily spend, scaled by sqrt(remaining days) like a random walk. Deliberately simple and explainable;
 * the "linear" projection (MTD / elapsed x days in month) is returned too so the two can be compared.
 */
@Service
@TenantScoped(readOnly = true)
public class ForecastService {


    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ForecastService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Pure projection maths, unit-tested on its own. */
    record Projection(BigDecimal projected, BigDecimal low, BigDecimal high, BigDecimal linear) {}

    static Projection project(BigDecimal mtd, BigDecimal dailyRunRate, double elapsedDays, int daysInMonth, double dailySigma) {
        double remaining = Math.max(0, daysInMonth - elapsedDays);
        BigDecimal projected = mtd.add(dailyRunRate.multiply(BigDecimal.valueOf(remaining)));
        BigDecimal band = BigDecimal.valueOf(1.28 * dailySigma * Math.sqrt(remaining));
        BigDecimal low = projected.subtract(band).max(mtd);
        BigDecimal linear = elapsedDays <= 0 ? mtd
                : mtd.divide(BigDecimal.valueOf(elapsedDays), 12, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(daysInMonth));
        return new Projection(CostCalculator.normalize(projected), CostCalculator.normalize(low),
                CostCalculator.normalize(projected.add(band)), CostCalculator.normalize(linear));
    }

    /** Day (fraction of now) at which cumulative spend reaches {@code limit} at {@code rate}/day, or null if never this month. */
    static LocalDate reachDate(Instant now, BigDecimal current, BigDecimal limit, BigDecimal ratePerDay, LocalDate monthEnd) {
        if (limit == null || limit.signum() <= 0) {
            return null;
        }
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (current.compareTo(limit) >= 0) {
            return today;
        }
        if (ratePerDay.signum() <= 0) {
            return null;
        }
        double days = limit.subtract(current).divide(ratePerDay, 6, RoundingMode.HALF_UP).doubleValue();
        LocalDate at = LocalDate.ofInstant(now.plusSeconds((long) (days * 86_400)), ZoneOffset.UTC);
        return monthEnd == null || !at.isAfter(monthEnd) ? at : null;
    }

    static double sigma(List<BigDecimal> values) {
        if (values.size() < 2) {
            return 0;
        }
        double mean = values.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double var = values.stream().mapToDouble(v -> Math.pow(v.doubleValue() - mean, 2)).sum() / (values.size() - 1);
        return Math.sqrt(var);
    }

    public Forecast forecast() {
        Instant now = clock.instant();
        YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        Instant monthStart = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        int daysInMonth = month.lengthOfMonth();
        double elapsed = Duration.between(monthStart, now).toSeconds() / 86_400.0;

        Map<LocalDate, BigDecimal> daily = new HashMap<>();
        jdbc.query("""
                select (created_at at time zone 'UTC')::date as d, sum(amount_usd) from ledger_entries
                where tenant_id = aethergate.current_tenant_id() and entry_type = 'SETTLE' and created_at >= ?
                group by 1""", (java.sql.ResultSet rs) -> {
            daily.put(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2));
        }, Timestamp.from(now.minus(Duration.ofDays(45))));

        BigDecimal mtd = sumSettled(monthStart);
        BigDecimal last7 = sumSettled(now.minus(Duration.ofDays(7)));
        BigDecimal prev7 = sumSettledBetween(now.minus(Duration.ofDays(14)), now.minus(Duration.ofDays(7)));
        BigDecimal runRate = last7.divide(BigDecimal.valueOf(7), 8, RoundingMode.HALF_UP);
        List<BigDecimal> last14 = new ArrayList<>();
        for (int i = 1; i <= 14; i++) {
            last14.add(daily.getOrDefault(today.minusDays(i), BigDecimal.ZERO));
        }
        double dailySigma = sigma(last14);
        Projection p = project(mtd, runRate, elapsed, daysInMonth, dailySigma);

        Map<String, Object> tenant = jdbc.queryForMap("""
                select hard_budget_limit_usd, available_balance_usd from tenants where id = aethergate.current_tenant_id()""");
        BigDecimal limit = CostCalculator.normalize((BigDecimal) tenant.get("hard_budget_limit_usd"));
        BigDecimal available = CostCalculator.normalize((BigDecimal) tenant.get("available_balance_usd"));
        double pct = AnalyticsService.ratio(p.projected(), limit) * 100;
        LocalDate monthEnd = month.atEndOfMonth();
        LocalDate limitOn = reachDate(now, mtd, limit, runRate, monthEnd);
        Double runway = runRate.signum() == 0 ? null : available.divide(runRate, 2, RoundingMode.HALF_UP).doubleValue();
        LocalDate exhausted = runway == null ? null : LocalDate.ofInstant(now.plusSeconds((long) (runway * 86_400)), ZoneOffset.UTC);
        double trend = prev7.signum() == 0 ? 0 : last7.subtract(prev7).divide(prev7, 4, RoundingMode.HALF_UP).doubleValue() * 100;

        List<ForecastPoint> points = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (int d = 1; d <= daysInMonth; d++) {
            LocalDate day = month.atDay(d);
            if (!day.isAfter(today)) {
                BigDecimal actual = daily.getOrDefault(day, BigDecimal.ZERO);
                cumulative = cumulative.add(actual);
                boolean isToday = day.equals(today);
                points.add(new ForecastPoint(day, CostCalculator.normalize(actual), CostCalculator.normalize(cumulative),
                        isToday ? CostCalculator.normalize(cumulative) : null, isToday ? CostCalculator.normalize(cumulative) : null,
                        isToday ? CostCalculator.normalize(cumulative) : null));
            } else {
                // cumulative projection at the END of that day
                double ahead = Duration.between(now, day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()).toSeconds() / 86_400.0;
                BigDecimal value = mtd.add(runRate.multiply(BigDecimal.valueOf(ahead)));
                BigDecimal band = BigDecimal.valueOf(1.28 * dailySigma * Math.sqrt(ahead));
                points.add(new ForecastPoint(day, null, null, CostCalculator.normalize(value),
                        CostCalculator.normalize(value.subtract(band).max(mtd)), CostCalculator.normalize(value.add(band))));
            }
        }

        String status = pct > 100 ? "OVER_LIMIT" : pct >= 90 ? "AT_RISK" : "ON_TRACK";
        return new Forecast(month.toString(), daysInMonth, round2(elapsed), mtd, CostCalculator.normalize(runRate), p.linear(),
                p.projected(), p.low(), p.high(), limit, round2(pct), limitOn, available, runway, exhausted, status,
                round2(trend), points, projects(monthStart, now, elapsed, daysInMonth));
    }

    private List<ProjectForecast> projects(Instant monthStart, Instant now, double elapsed, int daysInMonth) {
        Timestamp week = Timestamp.from(now.minus(Duration.ofDays(7)));
        return jdbc.query("""
                select p.id, p.name, p.monthly_budget_usd,
                       coalesce(sum(e.amount_usd) filter (where e.created_at >= ?), 0) as mtd,
                       coalesce(sum(e.amount_usd) filter (where e.created_at >= ?), 0) as last7
                from projects p
                left join ledger_entries e on e.tenant_id = p.tenant_id and e.project_id = p.id and e.entry_type = 'SETTLE'
                                           and e.created_at >= ?
                where p.tenant_id = aethergate.current_tenant_id() and p.status = 'ACTIVE'
                group by p.id, p.name, p.monthly_budget_usd
                order by mtd desc, p.name""", (rs, i) -> {
            BigDecimal mtd = rs.getBigDecimal("mtd");
            BigDecimal rate = rs.getBigDecimal("last7").divide(BigDecimal.valueOf(7), 8, RoundingMode.HALF_UP);
            BigDecimal projected = project(mtd, rate, elapsed, daysInMonth, 0).projected();
            BigDecimal budget = rs.getBigDecimal("monthly_budget_usd");
            Double pct = budget == null ? null : round2(AnalyticsService.ratio(projected, budget) * 100);
            String status = pct == null ? "NO_BUDGET" : pct > 100 ? "OVER_BUDGET" : pct >= 90 ? "AT_RISK" : "ON_TRACK";
            return new ProjectForecast(rs.getObject("id", UUID.class).toString(), rs.getString("name"),
                    CostCalculator.normalize(mtd), CostCalculator.normalize(rate), projected,
                    budget == null ? null : CostCalculator.normalize(budget), pct, status);
        }, Timestamp.from(monthStart), week, monthStart.isBefore(week.toInstant()) ? Timestamp.from(monthStart) : week);
    }

    private BigDecimal sumSettled(Instant since) {
        return sumSettledBetween(since, clock.instant().plus(Duration.ofDays(1)));
    }

    private BigDecimal sumSettledBetween(Instant since, Instant until) {
        BigDecimal v = jdbc.queryForObject("""
                select coalesce(sum(amount_usd), 0) from ledger_entries
                where tenant_id = aethergate.current_tenant_id() and entry_type = 'SETTLE' and created_at >= ? and created_at < ?""",
                BigDecimal.class, Timestamp.from(since), Timestamp.from(until));
        return CostCalculator.normalize(v);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
