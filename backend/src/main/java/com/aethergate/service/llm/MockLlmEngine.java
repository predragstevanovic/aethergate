package com.aethergate.service.llm;

import com.aethergate.config.AetherGateProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Built-in LLM simulator so the platform works end-to-end without paid API keys.
 *
 * <p>Answers are deterministic for a given prompt (same prompt → same text, which also makes
 * the semantic cache demonstrable), streamed word-by-word with realistic time-to-first-token
 * and inter-token delays. Sleeping is cheap here: callers run on virtual threads.
 */
@Component
public class MockLlmEngine {

    private static final Pattern CHUNKS = Pattern.compile("\\S+\\s*|\\s+");

    private record Topic(List<String> keywords, String answer) {}

    private static final List<Topic> TOPICS = List.of(
            new Topic(List.of("rls", "row-level", "row level", "isolation", "multi-tenant", "tenant"),
                    "Row-Level Security moves tenant isolation from application code into the database kernel. "
                            + "Each transaction binds the tenant with set_config('app.current_tenant_id', ..., true) and switches to a role "
                            + "without BYPASSRLS, so every SELECT, UPDATE and INSERT is filtered by a policy such as "
                            + "tenant_id = current_setting('app.current_tenant_id')::uuid. Even a query that forgets its WHERE clause "
                            + "cannot read another tenant's rows. The WITH CHECK clause closes the write side: a row can only be inserted "
                            + "for the tenant that is currently bound. Because the setting is transaction-local, it is safe behind a "
                            + "transaction-mode connection pooler."),
            new Topic(List.of("ledger", "double-entry", "reserve", "settle", "metering", "billing", "budget"),
                    "Token metering works like a card authorisation hold. Before calling the model, the gateway locks the tenant row "
                            + "and reserves the maximum possible cost: prompt tokens plus max_tokens, priced at the most expensive provider "
                            + "in the fallback chain. When the stream finishes, the exact cost is settled and the unused remainder is "
                            + "released back to the available balance. Each request therefore writes RESERVE = SETTLE + RELEASE into an "
                            + "append-only ledger, and a unique (tenant_id, request_id, entry_type) constraint makes retries idempotent. "
                            + "Twenty concurrent requests can never overdraft the balance, because the hold is taken under SELECT FOR UPDATE."),
            new Topic(List.of("cache", "semantic", "embedding", "pgvector", "vector", "similarity"),
                    "A semantic cache stores each prompt as a 384-dimensional embedding next to its completion. A new prompt is "
                            + "embedded and compared with the HNSW index using cosine distance; above a similarity of 0.96 the stored answer "
                            + "is replayed in a few milliseconds at zero inference cost. The threshold is deliberately strict: it should catch "
                            + "rephrasings of the same question, not merely related ones. Entries are tenant-scoped through Row-Level "
                            + "Security and expire after a TTL so answers do not go stale."),
            new Topic(List.of("circuit", "breaker", "failover", "fallback", "outage", "503", "resilience"),
                    "Circuit breakers protect the gateway from a failing upstream. Each provider has a breaker that counts recent "
                            + "failures; when the failure rate crosses the threshold it opens and calls are routed straight to the next "
                            + "provider in the tenant's priority list instead of waiting for timeouts. After a cool-down the breaker lets a "
                            + "few probe calls through (half-open) and closes again once they succeed. Failover happens before the first token "
                            + "is sent, so the client keeps a single, uninterrupted stream."),
            new Topic(List.of("virtual thread", "virtual threads", "loom", "java 21", "concurrency", "webflux", "reactive"),
                    "Java 21 virtual threads make blocking I/O cheap again. An LLM gateway spends almost all of its time waiting for "
                            + "upstream tokens, so one virtual thread per stream scales to tens of thousands of concurrent requests while the "
                            + "code stays imperative, debuggable and compatible with JDBC transactions. The main rule is to avoid pinning: "
                            + "prefer ReentrantLock over synchronized around blocking calls."),
            new Topic(List.of("rate limit", "rate-limit", "throttle", "token bucket", "rpm", "tpm"),
                    "A token bucket limits both requests per minute and tokens per minute. Each API key owns two buckets that refill "
                            + "continuously; a request is admitted only if both have capacity, otherwise the gateway answers 429 with a "
                            + "Retry-After header. Buckets live in memory for sub-millisecond checks and are synchronised to minute-window "
                            + "counters in PostgreSQL so several gateway instances share one view of usage."),
            new Topic(List.of("price", "pricing", "cost", "cheap", "expensive", "model"),
                    "Model choice is mostly a cost-quality trade-off. Small models such as gpt-4o-mini cost cents per million tokens "
                            + "and handle classification, extraction and short answers well; larger models are worth it for multi-step "
                            + "reasoning. Routing by task, caching repeated questions and capping max_tokens usually cut spend by more than "
                            + "half without visible quality loss."));

    private final AetherGateProperties.Mock timing;

    public MockLlmEngine(AetherGateProperties properties) {
        this.timing = properties.mock();
    }

    /** Deterministic completion text for a conversation (persona shapes the opening line only). */
    public String compose(String persona, List<ChatMessage> messages) {
        String question = lastUserMessage(messages);
        String lower = question.toLowerCase(Locale.ROOT);
        Random rnd = new Random(question.trim().toLowerCase(Locale.ROOT).hashCode());
        for (Topic topic : TOPICS) {
            for (String keyword : topic.keywords()) {
                if (lower.contains(keyword)) {
                    return topic.answer();
                }
            }
        }
        String[] openers = {"Here is a concise answer.", "Short version first, then the details.", "Good question."};
        String subject = question.length() > 120 ? question.substring(0, 117) + "..." : question;
        return openers[rnd.nextInt(openers.length)] + " You asked: \"" + subject + "\". "
                + "In practice the answer depends on three things: the constraints you must not violate, the failure modes you can "
                + "tolerate, and how the result will be measured. Start from the invariant, make it explicit in code or in the "
                + "database, and add an automated test that would fail if it were ever broken. This response was generated by the "
                + persona + " simulator, which is deterministic, so sending the same prompt again is a good way to see the semantic "
                + "cache return it instantly.";
    }

    /** Streams {@link #compose} with realistic pacing, honouring max_tokens. */
    public ProviderResult stream(String persona, List<ChatMessage> messages, int maxTokens, TokenSink sink) {
        String text = compose(persona, messages);
        sleep(between(timing.ttftMinMs(), timing.ttftMaxMs()));
        StringBuilder emitted = new StringBuilder();
        int tokens = 0;
        String finish = "stop";
        Matcher m = CHUNKS.matcher(text);
        List<String> chunks = new ArrayList<>();
        while (m.find()) {
            chunks.add(m.group());
        }
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            int cost = Math.max(1, TokenEstimator.count(chunk));
            if (tokens + cost > maxTokens) {
                finish = "length";
                break;
            }
            if (i > 0) {
                sleep(between(timing.tokenMinMs(), timing.tokenMaxMs()));
            }
            sink.onToken(chunk);
            emitted.append(chunk);
            tokens += cost;
        }
        return new ProviderResult(emitted.toString(), TokenEstimator.countPrompt(messages), tokens, false, finish);
    }

    static String lastUserMessage(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equalsIgnoreCase(messages.get(i).role())) {
                return messages.get(i).content();
            }
        }
        return messages.isEmpty() ? "" : messages.getLast().content();
    }

    private static int between(int min, int max) {
        return max <= min ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private static void sleep(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientAbortedException(e);
        }
    }
}
