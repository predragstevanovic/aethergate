package com.aethergate.tools;

import com.aethergate.config.TenantContext;
import com.aethergate.service.cache.HashingEmbedder;
import com.aethergate.service.webhook.AlertService;
import com.aethergate.service.llm.ChatMessage;
import com.aethergate.service.llm.MockLlmEngine;
import com.aethergate.service.metering.CostCalculator;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Generates 30 days of realistic, internally consistent history for the three seeded tenants and their
 * projects (cost centres, one archived mid-month, budgets respected exactly as the gateway enforces them):
 * business-hour traffic with weekday peaks and growth, repeated prompts that become semantic
 * cache HITs, occasional failovers and outages, card top-ups, and a warm cache for the
 * playground. Every money movement obeys the ledger invariants, which {@link LedgerAuditor}
 * verifies right afterwards.
 *
 * <p>Resetting demo history needs DELETE on the append-only ledger, so this maintenance path
 * (table owner only, one transaction) disables the immutability trigger and re-enables it
 * before commit. The application role can never do this.
 */
@Component
public class DemoDataSeeder {

    static final UUID VORTEX = UUID.fromString("7f1c2a9e-4b3d-4e8a-9c61-2d5e8f0a1b01");
    static final UUID HELIX = UUID.fromString("3b8e5d21-9a4c-4f7e-b2d6-81c0e7f9a402");
    static final UUID NIMBUS = UUID.fromString("c4d7a913-5e2b-4f80-a6c1-9b3e7d2f1c03");
    static final List<UUID> SEEDED = List.of(VORTEX, HELIX, NIMBUS);

    private record Model(String provider, String name, BigDecimal in, BigDecimal out) {}

    private record Plan(UUID tenant, String label, int requests, Model primary, Model fallback, List<String> prompts,
                        int minPrompt, int maxPrompt, int maxTokens, List<BigDecimal> topUps, List<ProjectSeed> projects) {}

    /**
     * A cost centre: {@code weight} = share of traffic (the rest is unassigned), {@code budget} = monthly cap the
     * seeder respects exactly like the gateway (a request that would cross it is dropped, as a 402 would),
     * {@code activeUntil} = fraction of the 30 days after which the project is archived (1.0 = still active).
     */
    private record ProjectSeed(String name, String description, BigDecimal budget, int weight, double activeUntil) {
        UUID id(UUID tenant) {
            return UUID.nameUUIDFromBytes((tenant + ":" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** A guardrail rule and the share of requests that triggered it (BLOCKed ones never reached the ledger). */
    private record GuardrailSeed(String name, String type, String pattern, String action, double rate) {}

    private static final Map<UUID, List<GuardrailSeed>> GUARDRAILS = Map.of(
            VORTEX, List.of(
                    new GuardrailSeed("Secrets in prompts", "REGEX", "(?i)(api[_-]?key|password|secret)\\s*[:=]\\s*\\S+",
                            "BLOCK", 0.004),
                    new GuardrailSeed("Customer e-mails", "PII_EMAIL", null, "REDACT", 0.03)),
            HELIX, List.of(
                    new GuardrailSeed("Unreleased hardware", "KEYWORDS", "Project Atlas, prototype X9", "BLOCK", 0.006)),
            NIMBUS, List.of(
                    new GuardrailSeed("Patient e-mails", "PII_EMAIL", null, "REDACT", 0.05),
                    new GuardrailSeed("Medical record numbers", "REGEX", "\\bMRN-\\d{6}\\b", "REDACT", 0.04),
                    new GuardrailSeed("Payment cards", "PII_CARD", null, "BLOCK", 0.005)));

    /**
     * A versioned prompt template: v1 (verbose) served the first part of the month, v2 (tighter) was published at
     * {@code publishAt} of the period. Requests attributed to v2 are the shorter completions, so the version table
     * shows the cost difference a prompt change makes.
     */
    private record PromptSeed(String name, String description, String system, String v1, String v2, String v2Note, double share) {}

    private static final Map<UUID, List<PromptSeed>> PROMPTS = Map.of(
            VORTEX, List.of(new PromptSeed("support-reply", "Answers customer tickets in the help-desk widget",
                    "You are a friendly support agent for {{product}}.",
                    "Answer the customer's question in detail, with examples and links where useful.\nCustomer: {{customer}}\nQuestion: {{question}}",
                    "Answer in at most three sentences. Offer a link only if it answers the question.\nCustomer: {{customer}}\nQuestion: {{question}}",
                    "Shorter answers: max 3 sentences", 0.25)),
            HELIX, List.of(new PromptSeed("error-code-explainer", "Technician help for robot fault codes",
                    "You are a maintenance assistant for {{robot_model}} warehouse robots.",
                    "Explain fault code {{code}} thoroughly: causes, diagnostics, and a full repair procedure.",
                    "Explain fault code {{code}} as a 5-step checklist. Mention the most likely cause first.",
                    "Checklist format", 0.3)),
            NIMBUS, List.of(new PromptSeed("discharge-summary", "Plain-language visit summaries for patients",
                    "You are a clinical documentation assistant for {{clinic}}. Never include identifiers.",
                    "Summarise this visit for the patient in plain language, covering diagnosis, medication and follow-up:\n{{visit_notes}}",
                    "Summarise this visit for the patient in at most 5 bullet points at a grade-6 reading level:\n{{visit_notes}}",
                    "5 bullets, grade-6 reading level", 0.3)));

    private static final List<String> VORTEX_PROMPTS = List.of(
            "Summarize this support ticket and suggest a reply",
            "Classify the sentiment of this customer review",
            "Extract the invoice number, total and due date from this email",
            "Write a SQL query that returns monthly active users by plan",
            "Explain row level security for our onboarding docs",
            "Generate three subject lines for the product launch email",
            "Translate this release note into German",
            "What is our refund policy for annual plans?",
            "Draft a changelog entry for the billing ledger fix",
            "Review this pull request diff for security issues",
            "How does the metering ledger reserve budget?",
            "Rewrite this paragraph in a friendlier tone",
            "Turn these meeting notes into action items",
            "Explain the difference between RPM and TPM rate limits",
            "What is a semantic cache and when does it help?",
            "Suggest tags for this knowledge base article");

    private static final List<String> HELIX_PROMPTS = List.of(
            "Plan a pick-and-place path for the gripper around obstacle B",
            "Summarize last night's robot telemetry anomalies",
            "Explain the torque spike on joint 3 during cycle 418",
            "Write a ROS2 launch file for the conveyor sensor array",
            "Convert this maintenance log into a structured checklist",
            "What does error E-217 mean on the servo controller?",
            "Draft a shift handover note from these alerts",
            "How do circuit breakers handle an upstream outage?",
            "Estimate battery runtime for a 6-hour warehouse shift",
            "Generate unit test cases for the inverse kinematics solver");

    private static final List<String> NIMBUS_PROMPTS = List.of(
            "Summarize this discharge note for the patient in plain language",
            "Extract medication names and dosages from this referral letter",
            "Draft an appointment reminder SMS in English and Spanish",
            "Classify this patient message by urgency",
            "Map these symptoms to ICD-10 candidate codes for coder review",
            "Explain our data retention policy for clinical chat logs",
            "Turn this triage call transcript into a structured intake form",
            "What is the difference between PHI and de-identified data?",
            "Write a FHIR query that lists today's lab results for a ward",
            "Rewrite this consent form paragraph at a 6th-grade reading level",
            "Summarize the on-call handover notes for the morning shift",
            "How does the metering ledger reserve budget?");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MockLlmEngine engine;
    private final AlertService alerts;

    public DemoDataSeeder(JdbcTemplate jdbc, PlatformTransactionManager txManager, MockLlmEngine engine, AlertService alerts) {
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.tx = new TransactionTemplate(txManager);
        this.engine = engine;
    }

    public List<String> seed() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<Plan> plans = List.of(
                new Plan(VORTEX, "Vortex AI Labs", 1450,
                        new Model("OPENAI", "gpt-4o", new BigDecimal("2.50"), new BigDecimal("10.00")),
                        new Model("ANTHROPIC", "claude-sonnet-4-5", new BigDecimal("3.00"), new BigDecimal("15.00")),
                        VORTEX_PROMPTS, 350, 2600, 1024, List.of(new BigDecimal("100"), new BigDecimal("50")),
                        List.of(new ProjectSeed("Support Copilot", "Drafts replies and summaries for the support inbox",
                                        new BigDecimal("5.00"), 42, 1.0),
                                new ProjectSeed("Docs Search", "Answers questions over the public knowledge base",
                                        new BigDecimal("1.50"), 24, 1.0),
                                new ProjectSeed("Growth Experiments", "Marketing copy and A/B subject lines (no cap)",
                                        null, 14, 1.0),
                                new ProjectSeed("Legacy Chatbot", "Old website widget, replaced by Support Copilot",
                                        new BigDecimal("3.00"), 8, 0.35))),
                new Plan(HELIX, "Helix Robotics", 900,
                        new Model("ANTHROPIC", "claude-haiku-4-5", new BigDecimal("1.00"), new BigDecimal("5.00")),
                        new Model("GEMINI", "gemini-2.5-flash", new BigDecimal("0.30"), new BigDecimal("2.50")),
                        HELIX_PROMPTS, 600, 4200, 1024, List.of(new BigDecimal("100")),
                        List.of(new ProjectSeed("Fleet Telemetry", "Nightly anomaly summaries for 40 warehouse robots",
                                        new BigDecimal("2.00"), 48, 1.0),
                                new ProjectSeed("Maintenance Assistant", "Technician Q&A on error codes and checklists",
                                        new BigDecimal("0.80"), 34, 1.0))),
                new Plan(NIMBUS, "Nimbus Health", 1200,
                        new Model("OPENAI", "gpt-4o-mini", new BigDecimal("0.15"), new BigDecimal("0.60")),
                        new Model("GEMINI", "gemini-2.5-flash", new BigDecimal("0.30"), new BigDecimal("2.50")),
                        NIMBUS_PROMPTS, 250, 1800, 800, List.of(new BigDecimal("100")),
                        List.of(new ProjectSeed("Patient Messaging", "Plain-language summaries and appointment reminders",
                                        new BigDecimal("0.25"), 44, 1.0),
                                new ProjectSeed("Clinical Coding", "ICD-10 candidate suggestions for human coders",
                                        new BigDecimal("0.12"), 30, 1.0),
                                new ProjectSeed("Research Sandbox", "Exploratory prompts by the data team (no cap)",
                                        null, 12, 1.0))));
        List<String> report = new ArrayList<>();
        for (Plan plan : plans) {
            report.add(tx.execute(s -> seedTenant(plan, now)));
            // alert rules are evaluated by the real service (its own RLS-scoped transaction), exactly as after a settlement
            int fired = TenantContext.callAs(plan.tenant(), () -> alerts.evaluate(null));
            for (ProjectSeed p : plan.projects()) {
                if (p.budget() != null && p.activeUntil() >= 1.0) {
                    fired += TenantContext.callAs(plan.tenant(), () -> alerts.evaluate(p.id(plan.tenant())));
                }
            }
            report.add(String.format("%-16s %d budget alert(s) fired", plan.label(), fired));
        }
        return report;
    }

    private String seedTenant(Plan plan, Instant now) {
        UUID t = plan.tenant();
        reset(t, plan);
        Random rnd = new Random(t.getMostSignificantBits() ^ 20261122L);
        Instant start = now.minus(Duration.ofDays(30));

        List<Instant> times = trafficTimes(plan.requests(), start, now, rnd);
        insertProjects(plan, start, now);
        Instant monthStart = now.atZone(ZoneOffset.UTC).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
        Map<UUID, BigDecimal> projectMtd = new HashMap<>();
        int budgetRejections = 0;
        List<Object[]> ledger = new ArrayList<>();
        List<Object[]> logs = new ArrayList<>();
        Map<String, Instant> lastSeen = new HashMap<>();
        Map<String, BigDecimal> lastCost = new HashMap<>();
        Map<String, Integer> lastTokens = new HashMap<>();

        BigDecimal available = BigDecimal.ZERO;
        BigDecimal topUpTotal = BigDecimal.ZERO;
        int topUpIndex = 0;
        int hits = 0;
        int failovers = 0;
        BigDecimal spend = BigDecimal.ZERO;
        BigDecimal saved = BigDecimal.ZERO;

        // initial credit, then a card top-up when the balance runs low
        available = available.add(plan.topUps().getFirst());
        topUpTotal = topUpTotal.add(plan.topUps().getFirst());
        ledger.add(row(t, "topup_initial_credit", "TOP_UP", plan.topUps().getFirst(), available, "Initial credit",
                start.minusSeconds(3600), null));
        topUpIndex++;

        for (int i = 0; i < times.size(); i++) {
            Instant at = times.get(i);
            if (topUpIndex < plan.topUps().size() && available.compareTo(new BigDecimal("40")) < 0
                    || topUpIndex < plan.topUps().size() && i == times.size() * 3 / 5) {
                BigDecimal amount = plan.topUps().get(topUpIndex++);
                available = available.add(amount);
                topUpTotal = topUpTotal.add(amount);
                ledger.add(row(t, "topup_card_" + topUpIndex, "TOP_UP", amount, available, "Card top-up", at.minusSeconds(120), null));
            }
            double progress = (double) Duration.between(start, at).toSeconds() / Duration.between(start, now).toSeconds();
            ProjectSeed project = pickProject(plan.projects(), progress, rnd); // by time: archived projects get no later traffic
            UUID projectId = project == null ? null : project.id(t);
            String prompt = pickPrompt(plan.prompts(), rnd);
            if (rnd.nextDouble() < 0.6) {
                prompt = prompt + " (ref #" + (10_000 + rnd.nextInt(90_000)) + ")"; // one-off context: never cacheable
            }
            String requestId = "req_seed_" + t.toString().substring(0, 4) + "_" + i;
            int promptTokens = plan.minPrompt() + rnd.nextInt(plan.maxPrompt() - plan.minPrompt());
            Instant seen = lastSeen.get(prompt);
            boolean cacheable = seen != null && Duration.between(seen, at).compareTo(Duration.ofHours(24)) < 0;

            if (cacheable && rnd.nextDouble() < 0.8) {
                hits++;
                BigDecimal original = lastCost.get(prompt);
                saved = saved.add(original);
                int ttft = 4 + rnd.nextInt(14);
                logs.add(log(t, requestId, projectId, "CACHE", plan.primary().name(), promptTokens, lastTokens.get(prompt),
                        BigDecimal.ZERO, original, ttft + rnd.nextInt(10), ttft, "HIT", false, 200, at));
                continue;
            }

            // same rule as the gateway: hold the worst case over the whole fallback chain
            BigDecimal hold = CostCalculator.estimatedMaxCost(promptTokens, plan.maxTokens(), plan.primary().in(), plan.primary().out())
                    .max(CostCalculator.estimatedMaxCost(promptTokens, plan.maxTokens(), plan.fallback().in(), plan.fallback().out()));
            if (available.compareTo(hold) < 0) {
                continue; // would have been a 402 — the seed keeps the demo account healthy instead
            }
            if (project != null && project.budget() != null && !at.isBefore(monthStart)
                    && projectMtd.getOrDefault(projectId, BigDecimal.ZERO).add(hold).compareTo(project.budget()) > 0) {
                budgetRejections++;
                continue; // the gateway would answer 402 project-budget-exceeded: nothing is written
            }
            available = available.subtract(hold);
            ledger.add(row(t, requestId, "RESERVE", hold, available, "Pre-flight hold: " + promptTokens + " prompt + "
                    + plan.maxTokens() + " max completion tokens", at, projectId));

            double roll = rnd.nextDouble();
            if (roll < 0.004) { // outage of both providers: whole hold released
                available = available.add(hold);
                ledger.add(row(t, requestId, "RELEASE", hold, available, "All providers unavailable", at.plusMillis(900),
                        projectId));
                logs.add(log(t, requestId, projectId, "NONE", plan.primary().name(), promptTokens, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                        900, 900, "MISS", true, 503, at));
                continue;
            }
            boolean failover = roll < 0.03;
            Model used = failover ? plan.fallback() : plan.primary();
            int completion = 60 + rnd.nextInt(Math.min(plan.maxTokens(), 700));
            BigDecimal cost = CostCalculator.actualCost(promptTokens, completion, used.in(), used.out());
            int ttft = (failover ? 450 : 220) + rnd.nextInt(failover ? 600 : 520);
            int latency = ttft + completion * (12 + rnd.nextInt(14));
            Instant done = at.plusMillis(latency);
            BigDecimal release = hold.subtract(cost);
            ledger.add(row(t, requestId, "SETTLE", cost, available, used.name() + ": " + promptTokens + " in / " + completion
                    + " out tokens", done, projectId));
            available = available.add(release);
            ledger.add(row(t, requestId, "RELEASE", release, available, "Unused hold returned", done, projectId));
            if (projectId != null && !done.isBefore(monthStart)) {
                projectMtd.merge(projectId, cost, BigDecimal::add);
            }
            logs.add(log(t, requestId, projectId, used.provider(), used.name(), promptTokens, completion, cost, BigDecimal.ZERO,
                    latency, ttft, "MISS", failover, 200, at));
            spend = spend.add(cost);
            if (failover) {
                failovers++;
            }
            lastSeen.put(prompt, at);
            lastCost.put(prompt, cost);
            lastTokens.put(prompt, completion);
        }

        jdbc.batchUpdate("""
                insert into ledger_entries (id, tenant_id, request_id, entry_type, amount_usd, balance_after_usd, description,
                                            created_at, project_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", ledger);
        jdbc.batchUpdate("""
                insert into llm_request_logs (id, tenant_id, request_id, project_id, provider_used, model_used, prompt_tokens,
                    completion_tokens, total_cost_usd, saved_cost_usd, latency_ms, ttft_ms, cache_status, failover_triggered,
                    status_code, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", logs);
        jdbc.update("update tenants set available_balance_usd = ?, reserved_balance_usd = 0 where id = ?", available, t);

        int cached = warmCache(t, plan, lastSeen, lastCost, lastTokens, now);
        int guardrailHits = seedGuardrails(t, times, rnd);
        int templated = seedPrompts(t, start, now, rnd);
        seedAlertRules(t, plan);
        return String.format("%-16s %5d requests | %4d cache hits | %3d failovers | %d projects, %d budget 402s"
                        + " | %d guardrail hits | %d template requests"
                        + " | spend $%s | saved $%s | balance $%s | %d cached prompts",
                plan.label(), logs.size(), hits, failovers, plan.projects().size(), budgetRejections, guardrailHits, templated,
                CostCalculator.normalize(spend).toPlainString(), CostCalculator.normalize(saved).toPlainString(),
                available.toPlainString(), cached);
    }

    /** Playground prompts answered in the last 24h stay in the semantic cache (same text the simulator produces). */
    private int warmCache(UUID t, Plan plan, Map<String, Instant> lastSeen, Map<String, BigDecimal> lastCost,
                          Map<String, Integer> lastTokens, Instant now) {
        int n = 0;
        for (Map.Entry<String, Instant> e : lastSeen.entrySet()) {
            if (e.getKey().contains("(ref #")) {
                continue;
            }
            Instant created = e.getValue();
            if (Duration.between(created, now).compareTo(Duration.ofHours(23)) > 0) {
                created = now.minus(Duration.ofMinutes(30L + n * 7L));
            }
            String key = "user: " + e.getKey();
            String text = engine.compose(plan.primary().name(), List.of(new ChatMessage("user", e.getKey())));
            jdbc.update("""
                    insert into semantic_cache (id, tenant_id, prompt_text, embedding, response_text, model_name,
                                                original_cost_usd, completion_tokens, hit_count, expires_at, created_at)
                    values (?, ?, ?, cast(? as extensions.vector), ?, 'auto', ?, ?, ?, ?, ?)""",
                    UUID.randomUUID(), t, key, HashingEmbedder.toVectorLiteral(HashingEmbedder.embed(key)), text,
                    lastCost.get(e.getKey()), lastTokens.get(e.getKey()), 3 + Math.abs(key.hashCode() % 40),
                    Timestamp.from(created.plus(Duration.ofHours(24))), Timestamp.from(created));
            n++;
        }
        return n;
    }

    private void reset(UUID t, Plan plan) {
        jdbc.update("delete from semantic_cache where tenant_id = ?", t);
        jdbc.update("delete from llm_request_logs where tenant_id = ?", t);
        jdbc.update("delete from rate_limit_windows where tenant_id = ?", t);
        jdbc.execute("alter table ledger_entries disable trigger trg_ledger_entries_append_only");
        jdbc.update("delete from ledger_entries where tenant_id = ?", t);
        jdbc.execute("alter table ledger_entries enable trigger trg_ledger_entries_append_only");
        jdbc.update("delete from prompt_templates where tenant_id = ?", t); // versions cascade; logs are already gone
        jdbc.update("delete from alert_rules where tenant_id = ?", t);      // firings cascade
        jdbc.update("delete from guardrail_events where tenant_id = ?", t);
        jdbc.update("delete from guardrail_rules where tenant_id = ?", t);
        jdbc.update("update api_keys set project_id = null where tenant_id = ?", t);
        jdbc.update("delete from projects where tenant_id = ?", t);
        jdbc.update("delete from provider_configs where tenant_id = ?", t);
        jdbc.update("""
                insert into provider_configs (tenant_id, provider, model_name, priority_order, input_cost_per_1m, output_cost_per_1m)
                values (?, ?, ?, 1, ?, ?), (?, ?, ?, 2, ?, ?)""",
                t, plan.primary().provider(), plan.primary().name(), plan.primary().in(), plan.primary().out(),
                t, plan.fallback().provider(), plan.fallback().name(), plan.fallback().in(), plan.fallback().out());
        jdbc.update("update api_keys set is_active = false where tenant_id = ? and key_prefix <> 'ag_live_demo'", t);
    }

    /** Weekday business-hour peaks, quieter weekends, ~40% growth across the month. */
    private static List<Instant> trafficTimes(int count, Instant start, Instant end, Random rnd) {
        List<Instant> out = new ArrayList<>(count);
        long span = Duration.between(start, end).toSeconds();
        while (out.size() < count) {
            Instant candidate = start.plusSeconds((long) (rnd.nextDouble() * span));
            ZonedDateTime z = candidate.atZone(ZoneOffset.UTC);
            double hour = z.getHour() + z.getMinute() / 60.0;
            double daily = 0.15 + Math.exp(-Math.pow((hour - 14) / 3.5, 2));
            double weekday = z.getDayOfWeek().getValue() >= 6 ? 0.45 : 1.0;
            double growth = 0.7 + 0.6 * Duration.between(start, candidate).toSeconds() / (double) span;
            if (rnd.nextDouble() < daily * weekday * growth / 1.6) {
                out.add(candidate);
            }
        }
        out.sort(null);
        return out;
    }

    /** Templates with two versions; a share of the request history is attributed to v1 before and v2 after publishing. */
    private int seedPrompts(UUID t, Instant start, Instant now, Random rnd) {
        int attributed = 0;
        for (PromptSeed p : PROMPTS.getOrDefault(t, List.of())) {
            UUID id = UUID.nameUUIDFromBytes((t + ":prompt:" + p.name()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Instant created = start.minus(Duration.ofDays(2));
            Instant published = start.plus(Duration.between(start, now).multipliedBy(6).dividedBy(10));
            jdbc.update("""
                    insert into prompt_templates (id, tenant_id, name, description, active_version, version, created_at, updated_at)
                    values (?, ?, ?, ?, 2, 2, ?, ?)""", id, t, p.name(), p.description(), Timestamp.from(created), Timestamp.from(published));
            String vars = String.join(",", com.aethergate.service.prompt.PromptTemplateEngine.variables(p.system(), p.v1()));
            jdbc.update("""
                    insert into prompt_template_versions (tenant_id, template_id, version_no, system_prompt, user_template, variables,
                    note, created_by_email, created_at) values (?, ?, 1, ?, ?, ?, 'initial version', 'seed', ?),
                    (?, ?, 2, ?, ?, ?, ?, 'seed', ?)""", t, id, p.system(), p.v1(), vars, Timestamp.from(created),
                    t, id, p.system(), p.v2(), vars, p.v2Note(), Timestamp.from(published.minus(Duration.ofHours(20))));
            Integer median = jdbc.queryForObject("""
                    select percentile_disc(0.5) within group (order by completion_tokens) from llm_request_logs
                    where tenant_id = ? and cache_status = 'MISS' and status_code = 200""", Integer.class, t);
            List<Object[]> updates = new ArrayList<>();
            jdbc.query("""
                    select id, created_at, completion_tokens from llm_request_logs
                    where tenant_id = ? and cache_status = 'MISS' and status_code = 200 and template_id is null order by created_at""",
                    rs -> {
                        boolean after = !rs.getTimestamp(2).toInstant().isBefore(published);
                        boolean fits = after ? rs.getInt(3) < median : rs.getInt(3) >= median;
                        if (fits && rnd.nextDouble() < p.share() * 2) {
                            updates.add(new Object[]{id, after ? 2 : 1, rs.getObject(1, UUID.class)});
                        }
                    }, t);
            jdbc.batchUpdate("update llm_request_logs set template_id = ?, template_version = ? where id = ?", updates);
            attributed += updates.size();
        }
        return attributed;
    }

    /** Workspace alerts at 50 % / 80 % and a 90 % alert for every active project with a budget. */
    private void seedAlertRules(UUID t, Plan plan) {
        jdbc.update("insert into alert_rules (tenant_id, project_id, threshold_pct) values (?, null, 50), (?, null, 80)", t, t);
        for (ProjectSeed p : plan.projects()) {
            if (p.budget() != null && p.activeUntil() >= 1.0) {
                jdbc.update("insert into alert_rules (tenant_id, project_id, threshold_pct) values (?, ?, 90)", t, p.id(t));
            }
        }
    }

    /** Rules created before the month started, and an event for each request that (would have) triggered one. */
    private int seedGuardrails(UUID t, List<Instant> times, Random rnd) {
        int hits = 0;
        Instant created = times.getFirst().minus(Duration.ofDays(3));
        for (GuardrailSeed g : GUARDRAILS.getOrDefault(t, List.of())) {
            UUID ruleId = UUID.nameUUIDFromBytes((t + ":guardrail:" + g.name()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.update("""
                    insert into guardrail_rules (id, tenant_id, name, rule_type, pattern, action, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)""", ruleId, t, g.name(), g.type(), g.pattern(), g.action(),
                    Timestamp.from(created), Timestamp.from(created));
            List<Object[]> events = new ArrayList<>();
            for (int i = 0; i < times.size(); i++) {
                if (rnd.nextDouble() < g.rate()) {
                    String requestId = "BLOCK".equals(g.action())
                            ? "req_blocked_" + t.toString().substring(0, 4) + "_" + i // never reached the ledger
                            : "req_seed_" + t.toString().substring(0, 4) + "_" + i;
                    events.add(new Object[]{t, ruleId, g.name(), g.action(), requestId, 1 + rnd.nextInt(2),
                            Timestamp.from(times.get(i))});
                }
            }
            jdbc.batchUpdate("""
                    insert into guardrail_events (tenant_id, rule_id, rule_name, action, request_id, matches, created_at)
                    values (?, ?, ?, ?, ?, ?, ?)""", events);
            hits += events.size();
        }
        return hits;
    }

    private void insertProjects(Plan plan, Instant start, Instant now) {
        long span = Duration.between(start, now).toSeconds();
        for (ProjectSeed p : plan.projects()) {
            boolean archived = p.activeUntil() < 1.0;
            Instant created = start.minus(Duration.ofDays(2));
            Instant archivedAt = archived ? start.plusSeconds((long) (span * p.activeUntil()) + 3600) : null;
            jdbc.update("""
                    insert into projects (id, tenant_id, name, description, monthly_budget_usd, status, created_at, updated_at,
                                          archived_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    p.id(plan.tenant()), plan.tenant(), p.name(), p.description(),
                    p.budget() == null ? null : CostCalculator.normalize(p.budget()), archived ? "ARCHIVED" : "ACTIVE",
                    Timestamp.from(created), Timestamp.from(archived ? archivedAt : created),
                    archivedAt == null ? null : Timestamp.from(archivedAt));
        }
    }

    /** Weighted pick among projects still active at this point of the month; leftover weight = unassigned key. */
    private static ProjectSeed pickProject(List<ProjectSeed> projects, double progress, Random rnd) {
        int roll = rnd.nextInt(100);
        int acc = 0;
        for (ProjectSeed p : projects) {
            acc += p.weight();
            if (roll < acc) {
                return progress <= p.activeUntil() ? p : null;
            }
        }
        return null;
    }

    /** Zipf-like: a few prompts are asked constantly (which is what makes caching pay off). */
    private static String pickPrompt(List<String> prompts, Random rnd) {
        int idx = (int) Math.floor(Math.pow(rnd.nextDouble(), 2.2) * prompts.size());
        return prompts.get(Math.min(idx, prompts.size() - 1));
    }

    @SuppressWarnings("java:S107")
    private static Object[] row(UUID tenant, String requestId, String type, BigDecimal amount, BigDecimal after,
                                String description, Instant at, UUID project) {
        return new Object[]{UUID.randomUUID(), tenant, requestId, type, CostCalculator.normalize(amount),
                CostCalculator.normalize(after), description, Timestamp.from(at), project};
    }

    @SuppressWarnings("java:S107")
    private static Object[] log(UUID tenant, String requestId, UUID project, String provider, String model, int prompt,
                                int completion, BigDecimal cost, BigDecimal saved, int latency, int ttft, String cache,
                                boolean failover, int status, Instant at) {
        return new Object[]{UUID.randomUUID(), tenant, requestId, project, provider, model, prompt, completion,
                CostCalculator.normalize(cost), CostCalculator.normalize(saved), latency, ttft, cache, failover, status,
                Timestamp.from(at)};
    }
}
