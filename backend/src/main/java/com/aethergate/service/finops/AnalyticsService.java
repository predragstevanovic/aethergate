package com.aethergate.service.finops;

import com.aethergate.config.TenantScoped;
import com.aethergate.service.metering.CostCalculator;
import com.aethergate.service.finops.FinOpsViews.Attribution;
import com.aethergate.service.finops.FinOpsViews.AttributionRow;
import com.aethergate.service.finops.FinOpsViews.EndUserRow;
import com.aethergate.service.finops.FinOpsViews.EndUsers;
import com.aethergate.service.finops.FinOpsViews.RequestPage;
import com.aethergate.service.finops.FinOpsViews.RequestRow;
import com.aethergate.service.finops.FinOpsViews.SessionDetail;
import com.aethergate.service.finops.FinOpsViews.SessionRow;
import com.aethergate.service.finops.FinOpsViews.SessionStep;
import com.aethergate.service.finops.FinOpsViews.Sessions;
import com.aethergate.service.finops.FinOpsViews.Window;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Showback and observability over the request log: spend by any dimension (project, model, provider, API key,
 * end user or a cost tag), end users, agent sessions and a filterable request explorer. Read-only, under RLS.
 */
@Service
@TenantScoped(readOnly = true)
public class AnalyticsService {

    private static final TypeReference<Map<String, String>> TAGS = new TypeReference<>() {};
    private static final java.util.regex.Pattern TAG_KEY = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9_.-]{0,39}");
    static final int MAX_GROUPS = 100;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public AnalyticsService(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** SQL for a grouping dimension: a stable key and a human label. Whitelisted; tag keys are bound as parameters. */
    record Dimension(String name, String keyExpr, String labelExpr, String tagKey) {}

    static Dimension dimension(String raw) {
        String d = raw == null || raw.isBlank() ? "project" : raw.strip();
        return switch (d.toLowerCase(Locale.ROOT)) {
            case "project" -> new Dimension("project", "coalesce(l.project_id::text, '')", "coalesce(p.name, 'Unassigned')", null);
            case "model" -> new Dimension("model", "l.model_used", "l.model_used", null);
            case "provider" -> new Dimension("provider", "l.provider_used", "l.provider_used", null);
            case "api_key" -> new Dimension("api_key", "coalesce(l.api_key_id::text, '')",
                    "coalesce(k.name || ' · ' || k.key_prefix, 'Console playground')", null);
            case "end_user" -> new Dimension("end_user", "coalesce(l.end_user_id, '')", "coalesce(l.end_user_id, 'Anonymous')", null);
            default -> {
                if (!d.startsWith("tag:") || !TAG_KEY.matcher(d.substring(4)).matches()) {
                    throw new IllegalArgumentException("dimension must be project, model, provider, api_key, end_user or tag:<key>");
                }
                yield new Dimension(d, "coalesce(l.tags ->> ?, '')", "coalesce(l.tags ->> ?, 'Untagged')", d.substring(4));
            }
        };
    }

    public Attribution attribution(String dimensionName, Window window) {
        Dimension dim = dimension(dimensionName);
        Timestamp since = since(window);
        List<Object> args = new ArrayList<>();
        if (dim.tagKey() != null) {
            args.add(dim.tagKey());
            args.add(dim.tagKey());
        }
        args.add(since);
        List<AttributionRow> raw = jdbc.query("""
                select %s as k, %s as label, count(*) as requests,
                       coalesce(sum(l.prompt_tokens), 0) as pt, coalesce(sum(l.completion_tokens), 0) as ct,
                       coalesce(sum(l.total_cost_usd), 0) as cost, coalesce(sum(l.saved_cost_usd), 0) as saved,
                       count(*) filter (where l.cache_status = 'HIT') as hits, round(avg(l.latency_ms)) as latency,
                       count(*) filter (where l.status_code >= 400) as errors
                from llm_request_logs l
                left join projects p on p.tenant_id = l.tenant_id and p.id = l.project_id
                left join api_keys k on k.tenant_id = l.tenant_id and k.id = l.api_key_id
                where l.tenant_id = aethergate.current_tenant_id() and l.created_at >= ?
                group by 1, 2 order by cost desc, requests desc limit %d
                """.formatted(dim.keyExpr(), dim.labelExpr(), MAX_GROUPS),
                (rs, i) -> new AttributionRow(rs.getString("k"), rs.getString("label"), rs.getLong("requests"), rs.getLong("pt"),
                        rs.getLong("ct"), rs.getBigDecimal("cost"), rs.getBigDecimal("saved"), 0,
                        rs.getLong("requests") == 0 ? 0 : (double) rs.getLong("hits") / rs.getLong("requests"),
                        rs.getBigDecimal("latency") == null ? null : rs.getBigDecimal("latency").intValue(), rs.getLong("errors")),
                args.toArray());
        Map<String, Object> totals = jdbc.queryForMap("""
                select coalesce(sum(total_cost_usd), 0) as cost, count(*) as requests,
                       coalesce(sum(total_cost_usd) filter (where tags <> '{}'::jsonb), 0) as tagged
                from llm_request_logs where tenant_id = aethergate.current_tenant_id() and created_at >= ?""", since);
        BigDecimal total = (BigDecimal) totals.get("cost");
        BigDecimal tagged = (BigDecimal) totals.get("tagged");
        List<AttributionRow> rows = raw.stream().map(r -> new AttributionRow(r.key(), r.label(), r.requests(), r.promptTokens(),
                r.completionTokens(), r.costUsd(), r.savedUsd(), ratio(r.costUsd(), total), r.cacheHitRatio(), r.avgLatencyMs(),
                r.errors())).toList();
        List<String> tagKeys = jdbc.queryForList("""
                select k from llm_request_logs l, jsonb_object_keys(l.tags) k
                where l.tenant_id = aethergate.current_tenant_id() and l.created_at >= ?
                group by k order by count(*) desc, k limit 20""", String.class, since);
        return new Attribution(dim.name(), window.label(), rows, CostCalculator.normalize(total),
                ((Number) totals.get("requests")).longValue(), tagKeys, ratio(tagged, total) * 100,
                CostCalculator.normalize(total.subtract(tagged)));
    }

    public EndUsers endUsers(Window window) {
        Timestamp since = since(window);
        Timestamp today = Timestamp.from(LocalDate.now(clock.withZone(ZoneOffset.UTC)).atStartOfDay(ZoneOffset.UTC).toInstant());
        BigDecimal cap = jdbc.queryForObject("select end_user_daily_cap_usd from tenants where id = aethergate.current_tenant_id()",
                BigDecimal.class);
        Map<String, Integer> blocked = new HashMap<>();
        jdbc.query("""
                select scope_key, blocked_count from cost_control_events
                where tenant_id = aethergate.current_tenant_id() and kind = 'END_USER_DAILY_CAP' and period = ?""",
                (ResultSet rs) -> { blocked.put(rs.getString(1), rs.getInt(2)); }, LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString());
        List<EndUserRow> users = jdbc.query("""
                select end_user_id, count(*) as requests, coalesce(sum(prompt_tokens + completion_tokens), 0) as tokens,
                       coalesce(sum(total_cost_usd), 0) as cost,
                       coalesce(sum(total_cost_usd) filter (where created_at >= ?), 0) as today,
                       count(distinct session_id) as sessions, max(created_at) as last_seen
                from llm_request_logs
                where tenant_id = aethergate.current_tenant_id() and created_at >= ? and end_user_id is not null
                group by end_user_id order by cost desc, requests desc limit %d""".formatted(MAX_GROUPS),
                (rs, i) -> {
                    BigDecimal todayUsd = rs.getBigDecimal("today");
                    return new EndUserRow(rs.getString("end_user_id"), rs.getLong("requests"), rs.getLong("tokens"),
                            rs.getBigDecimal("cost"), todayUsd, rs.getLong("sessions"), rs.getTimestamp("last_seen").toInstant(),
                            cap == null ? null : ratio(todayUsd, cap) * 100, blocked.getOrDefault(rs.getString("end_user_id"), 0));
                }, today, since);
        Map<String, Object> anon = jdbc.queryForMap("""
                select count(*) as n, coalesce(sum(total_cost_usd), 0) as cost from llm_request_logs
                where tenant_id = aethergate.current_tenant_id() and created_at >= ? and end_user_id is null""", since);
        return new EndUsers(window.label(), users, cap, ((Number) anon.get("n")).longValue(), (BigDecimal) anon.get("cost"));
    }

    private record Caps(BigDecimal sessionCap, Integer maxSteps) {}

    private Caps caps() {
        return jdbc.queryForObject("select session_cap_usd, session_max_requests from tenants where id = aethergate.current_tenant_id()",
                (rs, i) -> new Caps(rs.getBigDecimal(1), (Integer) rs.getObject(2)));
    }

    private Map<String, Integer> sessionBlocks() {
        Map<String, Integer> out = new HashMap<>();
        jdbc.query("""
                select scope_key, sum(blocked_count) from cost_control_events
                where tenant_id = aethergate.current_tenant_id() and kind in ('SESSION_BUDGET', 'SESSION_STEPS')
                group by scope_key""", (ResultSet rs) -> { out.put(rs.getString(1), rs.getInt(2)); });
        return out;
    }

    private static final String SESSION_SELECT = """
            select session_id, max(end_user_id) as end_user, min(created_at) as started, max(created_at) as last_at,
                   max(created_at + make_interval(secs => latency_ms / 1000.0)) as ended, count(*) as steps,
                   coalesce(sum(prompt_tokens), 0) as pt, coalesce(sum(completion_tokens), 0) as ct,
                   coalesce(sum(total_cost_usd), 0) as cost, count(*) filter (where status_code >= 400) as errors,
                   count(*) filter (where cache_status = 'HIT') as hits, array_agg(distinct model_used) as models
            from llm_request_logs
            """;

    public Sessions sessions(Window window, String query) {
        Caps caps = caps();
        Map<String, Integer> blocks = sessionBlocks();
        List<Object> args = new ArrayList<>();
        args.add(since(window));
        String filter = "";
        if (query != null && !query.isBlank()) {
            filter = " and (session_id ilike ? or end_user_id ilike ?)";
            String like = "%" + query.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
        }
        List<SessionRow> rows = jdbc.query(SESSION_SELECT + """
                where tenant_id = aethergate.current_tenant_id() and session_id is not null and created_at >= ?%s
                group by session_id order by max(created_at) desc limit %d""".formatted(filter, MAX_GROUPS),
                (rs, i) -> sessionRow(rs, caps, blocks), args.toArray());
        Map<String, Object> totals = jdbc.queryForMap("""
                select count(distinct session_id) as n, coalesce(sum(total_cost_usd), 0) as cost from llm_request_logs
                where tenant_id = aethergate.current_tenant_id() and session_id is not null and created_at >= ?""", since(window));
        return new Sessions(window.label(), rows, caps.sessionCap(), caps.maxSteps(), ((Number) totals.get("n")).longValue(),
                (BigDecimal) totals.get("cost"));
    }

    public SessionDetail session(String sessionId, CostControlService controls) {
        Caps caps = caps();
        Map<String, Integer> blocks = sessionBlocks();
        List<SessionRow> summary = jdbc.query(SESSION_SELECT + """
                where tenant_id = aethergate.current_tenant_id() and session_id = ? group by session_id""",
                (rs, i) -> sessionRow(rs, caps, blocks), sessionId);
        if (summary.isEmpty()) {
            throw new NoSuchElementException("Session not found");
        }
        Instant started = summary.getFirst().startedAt();
        BigDecimal[] running = {BigDecimal.ZERO};
        List<SessionStep> steps = jdbc.query("""
                select request_id, created_at, provider_used, model_used, prompt_tokens, completion_tokens, total_cost_usd,
                       latency_ms, cache_status, status_code, failover_triggered, routing_reason, tags::text
                from llm_request_logs where tenant_id = aethergate.current_tenant_id() and session_id = ?
                order by created_at, request_id limit 500""", (rs, i) -> {
            running[0] = running[0].add(rs.getBigDecimal("total_cost_usd"));
            Instant at = rs.getTimestamp("created_at").toInstant();
            return new SessionStep(i + 1, rs.getString("request_id"), at, at.toEpochMilli() - started.toEpochMilli(),
                    rs.getString("provider_used"), rs.getString("model_used"), rs.getInt("prompt_tokens"),
                    rs.getInt("completion_tokens"), rs.getBigDecimal("total_cost_usd"), CostCalculator.normalize(running[0]),
                    rs.getInt("latency_ms"), rs.getString("cache_status"), rs.getInt("status_code"),
                    rs.getBoolean("failover_triggered"), rs.getString("routing_reason"), tags(rs.getString(13)));
        }, sessionId);
        List<CostControlService.ControlEvent> sessionBlocks = controls.recentEvents(200).stream()
                .filter(e -> e.scopeKey().equals(sessionId) && e.kind().startsWith("SESSION_")).toList();
        return new SessionDetail(summary.getFirst(), steps, sessionBlocks);
    }

    private SessionRow sessionRow(ResultSet rs, Caps caps, Map<String, Integer> blocks) throws SQLException {
        Instant started = rs.getTimestamp("started").toInstant();
        Instant ended = rs.getTimestamp("ended").toInstant();
        BigDecimal cost = rs.getBigDecimal("cost");
        long steps = rs.getLong("steps");
        String id = rs.getString("session_id");
        return new SessionRow(id, rs.getString("end_user"), started, rs.getTimestamp("last_at").toInstant(),
                Math.max(0, ended.toEpochMilli() - started.toEpochMilli()), steps, rs.getLong("pt"), rs.getLong("ct"), cost,
                rs.getLong("errors"), rs.getLong("hits"), models(rs.getArray("models")), blocks.getOrDefault(id, 0),
                caps.sessionCap() == null ? null : ratio(cost, caps.sessionCap()) * 100,
                caps.maxSteps() == null ? null : steps * 100.0 / caps.maxSteps());
    }

    /** Filters: model, provider, project, api_key, end_user, session, tag (key:value), cache (HIT/MISS), status (ok/error), q. */
    public RequestPage requests(Window window, Map<String, String> filters, int page, int size) {
        int safeSize = Math.clamp(size, 1, 100);
        int safePage = Math.max(0, page);
        StringBuilder where = new StringBuilder(" where l.tenant_id = aethergate.current_tenant_id() and l.created_at >= ?");
        List<Object> args = new ArrayList<>();
        args.add(since(window));
        Map<String, String> applied = new LinkedHashMap<>();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            String v = f.getValue() == null ? "" : f.getValue().strip();
            if (v.isEmpty()) {
                continue;
            }
            applied.put(f.getKey(), v);
            switch (f.getKey()) {
                case "model" -> { where.append(" and l.model_used = ?"); args.add(v); }
                case "provider" -> { where.append(" and l.provider_used = ?"); args.add(v.toUpperCase(Locale.ROOT)); }
                case "project" -> {
                    if ("unassigned".equalsIgnoreCase(v)) {
                        where.append(" and l.project_id is null");
                    } else {
                        where.append(" and l.project_id = ?");
                        args.add(uuid(v, "project"));
                    }
                }
                case "api_key" -> { where.append(" and l.api_key_id = ?"); args.add(uuid(v, "api_key")); }
                case "end_user" -> { where.append(" and l.end_user_id = ?"); args.add(v); }
                case "session" -> { where.append(" and l.session_id = ?"); args.add(v); }
                case "tag" -> {
                    int sep = v.indexOf(':') > 0 ? v.indexOf(':') : v.indexOf('=');
                    if (sep <= 0) {
                        throw new IllegalArgumentException("tag filter must be key:value");
                    }
                    where.append(" and l.tags @> ?::jsonb");
                    args.add(json(Map.of(v.substring(0, sep).strip().toLowerCase(Locale.ROOT), v.substring(sep + 1).strip())));
                }
                case "cache" -> {
                    String c = v.toUpperCase(Locale.ROOT);
                    if (!c.equals("HIT") && !c.equals("MISS")) {
                        throw new IllegalArgumentException("cache must be HIT or MISS");
                    }
                    where.append(" and l.cache_status = ?");
                    args.add(c);
                }
                case "status" -> {
                    if ("error".equalsIgnoreCase(v)) {
                        where.append(" and l.status_code >= 400");
                    } else if ("ok".equalsIgnoreCase(v)) {
                        where.append(" and l.status_code < 400");
                    } else {
                        throw new IllegalArgumentException("status must be ok or error");
                    }
                }
                case "routing" -> { where.append(" and l.routing_reason = ?"); args.add(v.toUpperCase(Locale.ROOT)); }
                case "q" -> { where.append(" and l.request_id like ?"); args.add(v.replace("%", "").replace("_", "\\_") + "%"); }
                default -> throw new IllegalArgumentException("Unknown filter '" + f.getKey() + "'");
            }
        }
        Map<String, Object> agg = jdbc.queryForMap("select count(*) as n, coalesce(sum(l.total_cost_usd), 0) as cost, "
                + "coalesce(sum(l.prompt_tokens + l.completion_tokens), 0) as tokens from llm_request_logs l" + where, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);
        List<RequestRow> rows = jdbc.query("""
                select l.request_id, l.created_at, l.provider_used, l.model_used, l.requested_model, l.routing_reason,
                       p.name as project_name, k.key_prefix, l.end_user_id, l.session_id, l.tags::text as tags, l.prompt_tokens,
                       l.completion_tokens, l.total_cost_usd, l.saved_cost_usd, l.latency_ms, l.ttft_ms, l.cache_status,
                       l.failover_triggered, l.status_code, l.template_version
                from llm_request_logs l
                left join projects p on p.tenant_id = l.tenant_id and p.id = l.project_id
                left join api_keys k on k.tenant_id = l.tenant_id and k.id = l.api_key_id""" + where
                + " order by l.created_at desc, l.request_id desc limit ? offset ?",
                (rs, i) -> new RequestRow(rs.getString("request_id"), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("provider_used"), rs.getString("model_used"), rs.getString("requested_model"),
                        rs.getString("routing_reason"), rs.getString("project_name"),
                        rs.getString("key_prefix") == null ? "playground" : rs.getString("key_prefix"), rs.getString("end_user_id"),
                        rs.getString("session_id"), tags(rs.getString("tags")), rs.getInt("prompt_tokens"),
                        rs.getInt("completion_tokens"), rs.getBigDecimal("total_cost_usd"), rs.getBigDecimal("saved_cost_usd"),
                        rs.getInt("latency_ms"), rs.getInt("ttft_ms"), rs.getString("cache_status"),
                        rs.getBoolean("failover_triggered"), rs.getInt("status_code"), (Integer) rs.getObject("template_version")),
                pageArgs.toArray());
        return new RequestPage(rows, safePage, safeSize, ((Number) agg.get("n")).longValue(),
                CostCalculator.normalize((BigDecimal) agg.get("cost")), ((Number) agg.get("tokens")).longValue(), applied);
    }

    private Timestamp since(Window window) {
        return Timestamp.from(clock.instant().minus(window.duration()));
    }

    private Map<String, String> tags(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, TAGS);
        } catch (java.io.IOException e) {
            return Map.of();
        }
    }

    private String json(Map<String, String> value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid tag filter");
        }
    }

    private static List<String> models(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).sorted().toList();
    }

    private static UUID uuid(String v, String what) {
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + " must be an id");
        }
    }

    static double ratio(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0 || part == null) {
            return 0;
        }
        return part.divide(whole, 6, RoundingMode.HALF_UP).doubleValue();
    }
}
