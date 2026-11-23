package com.aethergate.service.guardrail;

import com.aethergate.config.TenantContext;
import com.aethergate.service.guardrail.GuardrailEngine.CompiledRule;
import com.aethergate.service.guardrail.GuardrailEngine.Verdict;
import com.aethergate.service.llm.ChatMessage;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Gateway step between rate limiting and the semantic cache. Rules are compiled once per tenant and cached;
 * CRUD publishes {@link GuardrailsChanged} and the cache entry is dropped after commit (30 s TTL is the safety
 * net for other instances). What fires is recorded; a BLOCK throws before any hold is taken.
 */
@Component
public class GuardrailEnforcer {

    private final GuardrailService service;
    private final Cache<UUID, List<CompiledRule>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();

    public GuardrailEnforcer(GuardrailService service) {
        this.service = service;
    }

    public Verdict enforce(UUID tenantId, String requestId, List<ChatMessage> messages) {
        List<CompiledRule> rules = cache.get(tenantId, t -> TenantContext.callAs(t, service::activeRules));
        if (rules.isEmpty()) {
            return new Verdict(null, messages, List.of());
        }
        Verdict verdict = GuardrailEngine.evaluate(rules, messages);
        if (!verdict.matches().isEmpty()) {
            TenantContext.runAs(tenantId, () -> service.recordEvents(requestId, verdict.matches()));
        }
        if (verdict.blocked()) {
            throw new GuardrailBlockedException(verdict.blockedBy().ruleName(), verdict.blockedBy().count());
        }
        return verdict;
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onChange(GuardrailsChanged event) {
        cache.invalidate(event.tenantId());
    }
}
