package com.aethergate.service.finops;

import com.aethergate.config.TenantContext;
import com.aethergate.config.TenantScoped;
import com.aethergate.service.finops.FinOpsViews.Anomalies;
import com.aethergate.service.finops.FinOpsViews.AnomalyView;
import com.aethergate.service.finops.FinOpsViews.HourPoint;
import com.aethergate.service.metering.CostCalculator;
import com.aethergate.service.webhook.WebhookEvents;
import com.aethergate.service.webhook.WebhookOutbox;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Cost anomaly detection on hourly spend: each hour is judged against the 168 hours (one week) before it with a robust
 * z-score ({@link RobustBaseline}). Detected hours are stored once per tenant and hour (unique constraint), and only
 * the insert that creates the row publishes the {@code cost.anomaly_detected} webhook, so alerts are exactly-once even
 * when many settlements trigger a scan at the same moment.
 */
@Service
public class AnomalyService {

    public static final int BASELINE_HOURS = 168;
    public static final int VIEW_HOURS = 48;

    private final JdbcTemplate jdbc;
    private final WebhookOutbox outbox;
    private final Clock clock;

    public AnomalyService(JdbcTemplate jdbc, WebhookOutbox outbox, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
    }

    private record Series(Instant[] hours, double[] cost) {}

    /** Hourly spend for the last {@code n} hours (oldest first), the current partial hour last. */
    private Series series(int n) {
        Instant current = clock.instant().truncatedTo(ChronoUnit.HOURS);
        Instant first = current.minus(Duration.ofHours(n - 1L));
        Instant[] hours = new Instant[n];
        double[] cost = new double[n];
        for (int i = 0; i < n; i++) {
            hours[i] = first.plus(Duration.ofHours(i));
        }
        jdbc.query("""
                select date_trunc('hour', created_at) as h, sum(total_cost_usd) from llm_request_logs
                where tenant_id = aethergate.current_tenant_id() and created_at >= ?
                group by 1""", (java.sql.ResultSet rs) -> {
            Instant h = rs.getTimestamp(1).toInstant();
            int idx = (int) Duration.between(first, h).toHours();
            if (idx >= 0 && idx < n) {
                cost[idx] = rs.getBigDecimal(2).doubleValue();
            }
        }, Timestamp.from(first));
        return new Series(hours, cost);
    }

    private static RobustBaseline.Verdict verdict(Series s, int idx) {
        double[] history = Arrays.copyOfRange(s.cost(), Math.max(0, idx - BASELINE_HOURS), idx);
        return RobustBaseline.evaluate(history, s.cost()[idx]);
    }

    @TenantScoped(readOnly = true)
    public Anomalies view() {
        Series s = series(VIEW_HOURS + BASELINE_HOURS);
        List<HourPoint> hours = new ArrayList<>();
        for (int i = BASELINE_HOURS; i < s.cost().length; i++) {
            RobustBaseline.Verdict v = verdict(s, i);
            hours.add(new HourPoint(s.hours()[i], usd(s.cost()[i]), usd(v.median()), usd(v.threshold()), v.anomalous()));
        }
        List<AnomalyView> stored = jdbc.query("""
                select bucket_start, observed_usd, baseline_usd, threshold_usd, z_score, severity, top_dimension, detected_at
                from cost_anomalies where tenant_id = aethergate.current_tenant_id() and bucket_start >= ?
                order by bucket_start desc limit 50""", (rs, i) -> new AnomalyView(rs.getTimestamp(1).toInstant(),
                rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5).doubleValue(), rs.getString(6),
                rs.getString(7), rs.getTimestamp(8).toInstant()), Timestamp.from(clock.instant().minus(Duration.ofDays(30))));
        return new Anomalies(hours, stored, RobustBaseline.Z_WARNING,
                "robust z-score: (hour - median) / (1.4826 x MAD) over the previous 168 hours");
    }

    /** Judges the last {@code lookbackHours} hours and stores new anomalies. Returns how many were new. */
    @TenantScoped
    public int scan(int lookbackHours) {
        int look = Math.clamp(lookbackHours, 1, VIEW_HOURS);
        Series s = series(look + BASELINE_HOURS);
        int created = 0;
        for (int i = BASELINE_HOURS; i < s.cost().length; i++) {
            RobustBaseline.Verdict v = verdict(s, i);
            if (!v.anomalous()) {
                continue;
            }
            Instant hour = s.hours()[i];
            String top = topDimension(hour, s.cost()[i]);
            int inserted = jdbc.update("""
                    insert into cost_anomalies (tenant_id, bucket_start, observed_usd, baseline_usd, threshold_usd, z_score,
                                                severity, top_dimension)
                    values (?, ?, ?, ?, ?, ?, ?, ?) on conflict (tenant_id, bucket_start) do nothing""",
                    TenantContext.require(), Timestamp.from(hour), usd(s.cost()[i]), usd(v.median()), usd(v.threshold()),
                    BigDecimal.valueOf(Math.min(v.z(), 99_999_999)).setScale(2, RoundingMode.HALF_UP), v.severity(), top);
            if (inserted == 1) {
                created++;
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("hour", hour.toString());
                data.put("observed_usd", usd(s.cost()[i]));
                data.put("baseline_usd", usd(v.median()));
                data.put("threshold_usd", usd(v.threshold()));
                data.put("z_score", Math.round(v.z() * 100) / 100.0);
                data.put("severity", v.severity());
                data.put("top_contributor", top);
                outbox.publish(WebhookEvents.COST_ANOMALY, data);
            }
        }
        return created;
    }

    /** "gpt-4o · session agent-7f3a · 82 %": who drove the spike. */
    private String topDimension(Instant hour, double total) {
        List<String> top = jdbc.query("""
                select model_used, session_id, end_user_id, sum(total_cost_usd) as cost from llm_request_logs
                where tenant_id = aethergate.current_tenant_id() and created_at >= ? and created_at < ?
                group by 1, 2, 3 order by cost desc limit 1""", (rs, i) -> {
            StringBuilder sb = new StringBuilder(rs.getString(1));
            if (rs.getString(2) != null) {
                sb.append(" · session ").append(rs.getString(2));
            } else if (rs.getString(3) != null) {
                sb.append(" · user ").append(rs.getString(3));
            }
            double share = total <= 0 ? 0 : rs.getBigDecimal(4).doubleValue() / total * 100;
            String label = sb.append(" · ").append(Math.round(share)).append(" %").toString();
            return label.length() > 200 ? label.substring(0, 200) : label;
        }, Timestamp.from(hour), Timestamp.from(hour.plus(Duration.ofHours(1))));
        return top.isEmpty() ? null : top.getFirst();
    }

    private static BigDecimal usd(double v) {
        return CostCalculator.normalize(BigDecimal.valueOf(v));
    }
}
