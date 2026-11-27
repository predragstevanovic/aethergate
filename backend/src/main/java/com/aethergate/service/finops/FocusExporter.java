package com.aethergate.service.finops;

import com.aethergate.config.TenantScoped;
import com.aethergate.service.metering.CostCalculator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Cost export in the FinOps Foundation's FOCUS format (FinOps Open Cost and Usage Specification, 1.2 column names),
 * so AI spend lands in the same FinOps tooling as cloud bills. One row per UTC day x project x provider x model x
 * API key x tag set. {@code BilledCost} sums to the ledger's settled spend; {@code ListCost} adds back what the
 * semantic cache saved. AetherGate-specific columns use the spec's {@code x_} prefix.
 */
@Service
@TenantScoped(readOnly = true)
public class FocusExporter {

    public static final List<String> COLUMNS = List.of("BillingAccountId", "BillingAccountName", "BillingCurrency",
            "BillingPeriodStart", "BillingPeriodEnd", "ChargePeriodStart", "ChargePeriodEnd", "ChargeCategory",
            "ChargeFrequency", "ChargeDescription", "BilledCost", "EffectiveCost", "ListCost", "ContractedCost",
            "ConsumedQuantity", "ConsumedUnit", "PricingQuantity", "PricingUnit", "PricingCategory", "ProviderName",
            "PublisherName", "InvoiceIssuerName", "ServiceCategory", "ServiceName", "ResourceId", "ResourceName",
            "ResourceType", "SkuId", "SubAccountId", "SubAccountName", "Tags", "x_ApiKeyPrefix", "x_Requests",
            "x_PromptTokens", "x_CompletionTokens", "x_CacheHits", "x_CacheSavingsUsd");

    private static final TypeReference<Map<String, String>> TAGS = new TypeReference<>() {};
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public FocusExporter(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public record Export(String filename, String csv, int rows, BigDecimal billedCostUsd) {}

    public static YearMonth parseMonth(String raw, Clock clock) {
        if (raw == null || raw.isBlank()) {
            return YearMonth.now(clock.withZone(ZoneOffset.UTC));
        }
        try {
            return YearMonth.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("month must be YYYY-MM");
        }
    }

    public Export export(String monthParam) {
        YearMonth month = parseMonth(monthParam, clock);
        Instant start = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<String, Object> tenant = jdbc.queryForMap("select id, name, slug from tenants where id = aethergate.current_tenant_id()");
        String accountId = tenant.get("id").toString();
        String accountName = (String) tenant.get("name");
        StringBuilder csv = new StringBuilder(String.join(",", COLUMNS)).append("\r\n");
        BigDecimal[] billed = {BigDecimal.ZERO};
        int[] rows = {0};
        jdbc.query("""
                select (l.created_at at time zone 'UTC')::date as day, l.provider_used, l.model_used, l.project_id, p.name as project,
                       k.key_prefix, l.tags::text as tags, count(*) as requests, coalesce(sum(l.prompt_tokens), 0) as pt,
                       coalesce(sum(l.completion_tokens), 0) as ct, coalesce(sum(l.total_cost_usd), 0) as cost,
                       coalesce(sum(l.saved_cost_usd), 0) as saved, count(*) filter (where l.cache_status = 'HIT') as hits
                from llm_request_logs l
                left join projects p on p.tenant_id = l.tenant_id and p.id = l.project_id
                left join api_keys k on k.tenant_id = l.tenant_id and k.id = l.api_key_id
                where l.tenant_id = aethergate.current_tenant_id() and l.created_at >= ? and l.created_at < ?
                  and (l.total_cost_usd > 0 or l.cache_status = 'HIT')
                group by 1, 2, 3, 4, 5, 6, 7
                order by 1, 2, 3, 5 nulls first, 6 nulls first, 7""", (java.sql.ResultSet rs) -> {
            LocalDate day = rs.getObject("day", LocalDate.class);
            String provider = rs.getString("provider_used");
            String model = rs.getString("model_used");
            BigDecimal cost = CostCalculator.normalize(rs.getBigDecimal("cost"));
            BigDecimal saved = CostCalculator.normalize(rs.getBigDecimal("saved"));
            long tokens = rs.getLong("pt") + rs.getLong("ct");
            UUID projectId = rs.getObject("project_id", UUID.class);
            String keyPrefix = rs.getString("key_prefix") == null ? "playground" : rs.getString("key_prefix");
            boolean cache = "CACHE".equals(provider);
            String vendor = vendor(provider);
            Map<String, String> tags = new TreeMap<>(parse(rs.getString("tags")));
            tags.put("aethergate/api-key", keyPrefix);
            tags.put("aethergate/project", rs.getString("project") == null ? "unassigned" : rs.getString("project"));
            row(csv, accountId, accountName, "USD", start, end,
                    day.atStartOfDay(ZoneOffset.UTC).toInstant(), day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    "Usage", "Usage-Based", (cache ? "Semantic cache hit for " : "LLM tokens on ") + model,
                    cost, cost, cost.add(saved), cost, tokens, "Tokens",
                    BigDecimal.valueOf(tokens).divide(MILLION, MathContext.DECIMAL64).stripTrailingZeros().toPlainString(),
                    "1M Tokens", "Standard", vendor, vendor, "AetherGate", "AI and Machine Learning",
                    cache ? "AetherGate Semantic Cache" : vendor + " API", model, model, cache ? "Cached Completion" : "LLM Model",
                    provider.toLowerCase(Locale.ROOT) + ":" + model, projectId == null ? "unassigned" : projectId.toString(),
                    rs.getString("project") == null ? "Unassigned" : rs.getString("project"), toJson(tags), keyPrefix,
                    rs.getLong("requests"), rs.getLong("pt"), rs.getLong("ct"), rs.getLong("hits"), saved);
            billed[0] = billed[0].add(cost);
            rows[0]++;
        }, Timestamp.from(start), Timestamp.from(end));
        String filename = "aethergate-focus-" + tenant.get("slug") + "-" + month + ".csv";
        return new Export(filename, csv.toString(), rows[0], CostCalculator.normalize(billed[0]));
    }

    static String vendor(String provider) {
        return switch (provider) {
            case "OPENAI" -> "OpenAI";
            case "ANTHROPIC" -> "Anthropic";
            case "GEMINI" -> "Google";
            case "CACHE" -> "AetherGate";
            default -> "AetherGate Simulator";
        };
    }

    private static void row(StringBuilder csv, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(cell(values[i]));
        }
        csv.append("\r\n");
    }

    /** RFC 4180 quoting; a leading = + - @ is prefixed with ' so a spreadsheet never evaluates a cell (CSV injection). */
    static String cell(Object v) {
        if (v == null) {
            return "";
        }
        String s = v instanceof BigDecimal b ? b.toPlainString() : v.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !(v instanceof Number)) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private Map<String, String> parse(String raw) {
        try {
            return raw == null ? Map.of() : json.readValue(raw, TAGS);
        } catch (java.io.IOException e) {
            return Map.of();
        }
    }

    private String toJson(Map<String, String> tags) {
        try {
            return json.writeValueAsString(tags);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }
}
