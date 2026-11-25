package com.aethergate.service.prompt;

import com.aethergate.config.TenantContext;
import com.aethergate.config.TenantScoped;
import com.aethergate.service.ResourceConflictException;
import com.aethergate.service.StaleVersionException;
import com.aethergate.service.TemplateRef;
import com.aethergate.service.llm.ChatMessage;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Versioned prompt templates ("prompts as code, without a deploy").
 *
 * <ul>
 *   <li>Versions are immutable (a DB trigger rejects UPDATE): editing a prompt always creates version N+1.</li>
 *   <li>Exactly one version is ACTIVE; publishing or rolling back is a pointer move guarded by If-Match.</li>
 *   <li>Every request made through a template records the template id and version, so cost, tokens, latency and
 *       cache hit-rate can be compared version against version.</li>
 *   <li>A template that has served traffic cannot be deleted (the request log references it).</li>
 * </ul>
 */
@Service
public class PromptService {

    public static final int MAX_TEMPLATES = 50;
    public static final int MAX_VERSIONS = 100;
    static final int MAX_SYSTEM = 4000;
    static final int MAX_USER = 8000;

    private final JdbcTemplate jdbc;

    public PromptService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TemplateDraft(String name, String description, String systemPrompt, String userTemplate, String note) {}

    public record TemplateView(UUID id, String name, String description, int activeVersion, int latestVersion,
                               List<String> variables, long requests30d, BigDecimal cost30d, long version, Instant updatedAt) {}

    public record VersionView(int version, String systemPrompt, String userTemplate, List<String> variables, String note,
                              String createdBy, Instant createdAt, boolean active, long requests, BigDecimal avgCostUsd,
                              double avgPromptTokens, double avgCompletionTokens, double avgLatencyMs, double cacheHitRate) {}

    public record TemplateDetail(TemplateView template, List<VersionView> versions) {}

    public record Rendered(List<ChatMessage> messages, TemplateRef ref, String templateName) {}

    @TenantScoped(readOnly = true)
    public List<TemplateView> list() {
        return views(null);
    }

    @TenantScoped(readOnly = true)
    public TemplateDetail get(UUID id) {
        TemplateView t = views(id).stream().findFirst().orElseThrow(PromptService::notFound);
        List<VersionView> versions = jdbc.query("""
                select v.version_no, v.system_prompt, v.user_template, v.variables, v.note, v.created_by_email, v.created_at,
                       coalesce(s.requests, 0) as requests, coalesce(s.avg_cost, 0) as avg_cost, coalesce(s.avg_in, 0) as avg_in,
                       coalesce(s.avg_out, 0) as avg_out, coalesce(s.avg_latency, 0) as avg_latency, coalesce(s.hit_rate, 0) as hit_rate
                from prompt_template_versions v
                left join lateral (select count(*) as requests, avg(total_cost_usd) as avg_cost, avg(prompt_tokens) as avg_in,
                                          avg(completion_tokens) as avg_out, avg(latency_ms) as avg_latency,
                                          avg(case when cache_status = 'HIT' then 1.0 else 0.0 end) as hit_rate
                                   from llm_request_logs l where l.tenant_id = v.tenant_id and l.template_id = v.template_id
                                   and l.template_version = v.version_no) s on true
                where v.tenant_id = aethergate.current_tenant_id() and v.template_id = ?
                order by v.version_no desc""", (rs, i) -> version(rs, t.activeVersion()), id);
        return new TemplateDetail(t, versions);
    }

    @TenantScoped
    public TemplateDetail create(TemplateDraft d, String authorEmail) {
        String name = name(d.name());
        Integer n = jdbc.queryForObject("select count(*) from prompt_templates where tenant_id = aethergate.current_tenant_id()",
                Integer.class);
        if (n != null && n >= MAX_TEMPLATES) {
            throw new ResourceConflictException("A workspace can have at most " + MAX_TEMPLATES + " prompt templates");
        }
        requireUniqueName(name, null);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into prompt_templates (id, tenant_id, name, description, active_version) values (?, ?, ?, ?, 1)",
                id, TenantContext.require(), name, description(d.description()));
        insertVersion(id, 1, d, authorEmail);
        return get(id);
    }

    /** Name and description only (the prompt text is versioned). If-Match on the template's version. */
    @TenantScoped
    public TemplateDetail rename(UUID id, long expectedVersion, String newName, String newDescription) {
        long current = lockVersion(id);
        if (current != expectedVersion) {
            throw new StaleVersionException(expectedVersion, current);
        }
        String name = name(newName);
        requireUniqueName(name, id);
        jdbc.update("""
                update prompt_templates set name = ?, description = ?, version = version + 1, updated_at = now() where id = ?""",
                name, description(newDescription), id);
        return get(id);
    }

    /** A new immutable version; it is NOT active until published, so drafts never reach production traffic. */
    @TenantScoped
    public TemplateDetail addVersion(UUID id, TemplateDraft d, String authorEmail) {
        lockVersion(id); // serialises concurrent "new version" clicks on the template row
        Integer latest = jdbc.queryForObject("select max(version_no) from prompt_template_versions where template_id = ?",
                Integer.class, id);
        int next = (latest == null ? 0 : latest) + 1;
        if (next > MAX_VERSIONS) {
            throw new ResourceConflictException("A template can have at most " + MAX_VERSIONS + " versions");
        }
        insertVersion(id, next, d, authorEmail);
        jdbc.update("update prompt_templates set version = version + 1, updated_at = now() where id = ?", id);
        return get(id);
    }

    /** Publish a version, or roll back to an older one. If-Match on the template's version. */
    @TenantScoped
    public TemplateDetail activate(UUID id, long expectedVersion, int versionNo) {
        long current = lockVersion(id);
        if (current != expectedVersion) {
            throw new StaleVersionException(expectedVersion, current);
        }
        Integer exists = jdbc.queryForObject("select count(*) from prompt_template_versions where template_id = ? and version_no = ?",
                Integer.class, id, versionNo);
        if (exists == null || exists == 0) {
            throw new NoSuchElementException("Version " + versionNo + " not found");
        }
        jdbc.update("update prompt_templates set active_version = ?, version = version + 1, updated_at = now() where id = ?",
                versionNo, id);
        return get(id);
    }

    @TenantScoped
    public void delete(UUID id) {
        lockVersion(id);
        Integer used = jdbc.queryForObject("select count(*) from llm_request_logs where tenant_id = aethergate.current_tenant_id() and template_id = ?",
                Integer.class, id);
        if (used != null && used > 0) {
            throw new ResourceConflictException("This template has served " + used + " request(s); its history is kept, so it cannot be deleted");
        }
        jdbc.update("delete from prompt_templates where id = ?", id);
    }

    /** Renders the active (or a specific) version with the given values; used by the playground, the gateway and preview. */
    @TenantScoped(readOnly = true)
    public Rendered render(String idOrName, Integer versionNo, Map<String, String> values) {
        List<Object[]> t = jdbc.query("""
                select id, name, active_version from prompt_templates where tenant_id = aethergate.current_tenant_id()
                and (id::text = ? or lower(name) = lower(?))""", (rs, i) -> new Object[] {rs.getObject(1, UUID.class),
                rs.getString(2), rs.getInt(3)}, idOrName, idOrName);
        if (t.isEmpty()) {
            throw new NoSuchElementException("Prompt template '" + idOrName + "' not found");
        }
        UUID id = (UUID) t.getFirst()[0];
        int v = versionNo == null ? (int) t.getFirst()[2] : versionNo;
        List<String[]> row = jdbc.query("""
                select system_prompt, user_template, variables from prompt_template_versions where template_id = ? and version_no = ?""",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, id, v);
        if (row.isEmpty()) {
            throw new NoSuchElementException("Version " + v + " not found");
        }
        Map<String, String> vals = values == null ? Map.of() : values;
        PromptTemplateEngine.requireValues(split(row.getFirst()[2]), vals);
        List<ChatMessage> messages = new ArrayList<>();
        String system = PromptTemplateEngine.render(row.getFirst()[0], vals);
        if (system != null && !system.isBlank()) {
            messages.add(new ChatMessage("system", system));
        }
        messages.add(new ChatMessage("user", PromptTemplateEngine.render(row.getFirst()[1], vals)));
        return new Rendered(messages, new TemplateRef(id, v), (String) t.getFirst()[1]);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private void insertVersion(UUID templateId, int no, TemplateDraft d, String author) {
        String system = d.systemPrompt() == null || d.systemPrompt().isBlank() ? null : d.systemPrompt().strip();
        String user = d.userTemplate() == null ? "" : d.userTemplate().strip();
        if (user.isEmpty()) {
            throw new IllegalArgumentException("The user message template must not be empty");
        }
        if (user.length() > MAX_USER || (system != null && system.length() > MAX_SYSTEM)) {
            throw new IllegalArgumentException("Templates: system prompt up to " + MAX_SYSTEM + ", user message up to " + MAX_USER + " characters");
        }
        List<String> vars = PromptTemplateEngine.variables(system, user);
        String note = d.note() == null || d.note().isBlank() ? null : d.note().strip();
        if (note != null && note.length() > 200) {
            throw new IllegalArgumentException("Version note must be at most 200 characters");
        }
        jdbc.update("""
                insert into prompt_template_versions (tenant_id, template_id, version_no, system_prompt, user_template, variables,
                note, created_by_email) values (?, ?, ?, ?, ?, ?, ?, ?)""", TenantContext.require(), templateId, no, system, user,
                String.join(",", vars), note, author);
    }

    private long lockVersion(UUID id) {
        List<Long> v = jdbc.queryForList("select version from prompt_templates where id = ? for update", Long.class, id);
        if (v.isEmpty()) {
            throw notFound();
        }
        return v.getFirst();
    }

    private void requireUniqueName(String name, UUID exceptId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from prompt_templates where tenant_id = aethergate.current_tenant_id() and lower(name) = lower(?)
                and (?::uuid is null or id <> ?::uuid)""", Integer.class, name, exceptId, exceptId);
        if (n != null && n > 0) {
            throw new ResourceConflictException("A prompt template called '" + name + "' already exists");
        }
    }

    private List<TemplateView> views(UUID onlyId) {
        return jdbc.query("""
                select t.id, t.name, t.description, t.active_version, t.version, t.updated_at,
                       (select max(version_no) from prompt_template_versions v where v.template_id = t.id) as latest,
                       (select variables from prompt_template_versions v where v.template_id = t.id and v.version_no = t.active_version) as vars,
                       coalesce(s.requests, 0) as requests, coalesce(s.cost, 0) as cost
                from prompt_templates t
                left join lateral (select count(*) as requests, sum(total_cost_usd) as cost from llm_request_logs l
                                   where l.tenant_id = t.tenant_id and l.template_id = t.id
                                   and l.created_at >= now() - interval '30 days') s on true
                where t.tenant_id = aethergate.current_tenant_id() and (?::uuid is null or t.id = ?::uuid)
                order by t.name""", (rs, i) -> new TemplateView(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("description"), rs.getInt("active_version"), rs.getInt("latest"), split(rs.getString("vars")),
                rs.getLong("requests"), rs.getBigDecimal("cost"), rs.getLong("version"), rs.getTimestamp("updated_at").toInstant()),
                onlyId, onlyId);
    }

    private static VersionView version(ResultSet rs, int active) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        int no = rs.getInt("version_no");
        return new VersionView(no, rs.getString("system_prompt"), rs.getString("user_template"), split(rs.getString("variables")),
                rs.getString("note"), rs.getString("created_by_email"), created.toInstant(), no == active, rs.getLong("requests"),
                rs.getBigDecimal("avg_cost"), rs.getDouble("avg_in"), rs.getDouble("avg_out"), rs.getDouble("avg_latency"),
                rs.getDouble("hit_rate"));
    }

    private static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : Arrays.stream(csv.split(",")).map(String::strip).toList();
    }

    private static String name(String raw) {
        String n = raw == null ? "" : raw.strip();
        if (n.length() < 2 || n.length() > 80) {
            throw new IllegalArgumentException("Template name must be 2-80 characters");
        }
        return n;
    }

    private static String description(String raw) {
        String d = raw == null ? null : raw.strip();
        if (d != null && d.length() > 280) {
            throw new IllegalArgumentException("Description must be at most 280 characters");
        }
        return d == null || d.isEmpty() ? null : d;
    }

    private static NoSuchElementException notFound() {
        return new NoSuchElementException("Prompt template not found");
    }
}
