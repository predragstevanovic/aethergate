package com.aethergate.service.tenant;

import com.aethergate.config.AetherGateProperties;
import com.aethergate.config.TenantContext;
import com.aethergate.config.TenantScoped;
import com.aethergate.domain.model.ApiKey;
import com.aethergate.domain.model.Project;
import com.aethergate.domain.model.ProviderConfig;
import com.aethergate.domain.model.ProviderType;
import com.aethergate.domain.repository.ApiKeyRepository;
import com.aethergate.domain.repository.ProjectRepository;
import com.aethergate.domain.repository.ProviderConfigRepository;
import com.aethergate.service.llm.LlmRouterService;
import com.aethergate.service.llm.RouteTarget;
import com.aethergate.service.metering.CostCalculator;
import com.aethergate.service.ResourceConflictException;
import com.aethergate.service.metering.MeteringLedgerService;
import com.aethergate.service.project.ProjectArchivedException;
import com.aethergate.service.ratelimit.RateLimiterService;
import com.aethergate.service.tenant.AdminViews.ApiKeyView;
import com.aethergate.service.tenant.AdminViews.CreatedApiKey;
import com.aethergate.service.tenant.AdminViews.KeyPolicyDraft;
import com.aethergate.service.tenant.AdminViews.RotatedApiKey;
import com.aethergate.service.StaleVersionException;
import com.aethergate.service.webhook.WebhookEvents;
import com.aethergate.service.webhook.WebhookOutbox;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import com.aethergate.service.tenant.AdminViews.ProviderView;
import com.aethergate.service.tenant.AdminViews.TopUpResult;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Tenant self-service: API keys, provider routing order, credit top-ups. Everything runs under RLS. */
@Service
public class TenantAdminService {

    static final int MAX_ACTIVE_KEYS = 20;
    static final int MAX_PROVIDERS = 6;
    static final BigDecimal MAX_PRICE_PER_1M = new BigDecimal("1000");
    static final String PUBLIC_DEMO_PREFIX = "ag_live_demo";

    private final ApiKeyRepository apiKeys;
    private final ProviderConfigRepository providers;
    private final ProjectRepository projects;
    private final MeteringLedgerService ledger;
    private final TenantDirectoryService directory;
    private final RateLimiterService rateLimiter;
    private final LlmRouterService router;
    private final BigDecimal maxTopUp;
    private final WebhookOutbox outbox;
    private final Clock clock;
    static final int MAX_POLICY_ENTRIES = 10;
    static final int MAX_GRACE_HOURS = 168;

    @SuppressWarnings("java:S107")
    public TenantAdminService(ApiKeyRepository apiKeys, ProviderConfigRepository providers, ProjectRepository projects,
                              MeteringLedgerService ledger, TenantDirectoryService directory, RateLimiterService rateLimiter,
                              LlmRouterService router, AetherGateProperties properties, WebhookOutbox outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
        this.apiKeys = apiKeys;
        this.providers = providers;
        this.projects = projects;
        this.ledger = ledger;
        this.directory = directory;
        this.rateLimiter = rateLimiter;
        this.router = router;
        this.maxTopUp = properties.metering().maxTopUpUsd();
    }

    @TenantScoped(readOnly = true)
    public List<ApiKeyView> listKeys() {
        return apiKeys.findAllByOrderByCreatedAtDesc().stream().map(this::view).toList();
    }

    @TenantScoped
    public CreatedApiKey createKey(String name, int rpm, int tpm) {
        return createKey(name, rpm, tpm, null);
    }

    /** {@code projectId}: optional cost centre; it must be an ACTIVE project of this tenant (RLS makes others invisible). */
    @TenantScoped
    public CreatedApiKey createKey(String name, int rpm, int tpm, UUID projectId) {
        long active = apiKeys.findAllByOrderByCreatedAtDesc().stream().filter(ApiKey::isActive).count();
        if (active >= MAX_ACTIVE_KEYS) {
            throw new IllegalArgumentException("A workspace can have at most " + MAX_ACTIVE_KEYS + " active keys");
        }
        String raw = ApiKeys.generate();
        if (projectId != null) {
            requireActiveProject(projectId);
        }
        ApiKey key = apiKeys.save(new ApiKey(TenantContext.require(), name.strip(), ApiKeys.displayPrefix(raw),
                ApiKeys.sha256Hex(raw), rpm, tpm, projectId));
        return new CreatedApiKey(view(key), raw);
    }

    @TenantScoped
    public ApiKeyView revokeKey(UUID keyId) {
        ApiKey key = apiKeys.findById(keyId).orElseThrow(() -> new NoSuchElementException("API key not found"));
        if (PUBLIC_DEMO_PREFIX.equals(key.getKeyPrefix())) {
            throw new IllegalArgumentException("The public demo key is shared by every visitor and cannot be revoked");
        }
        key.revoke();
        directory.evictKey(key.getKeyHash());
        rateLimiter.invalidate(key.getId());
        return view(key);
    }

    /**
     * Replaces a key's name, limits and restrictions. Optimistic (If-Match): two admins editing the same key cannot
     * silently overwrite each other. The cached principal is evicted, so the next request already sees the policy.
     */
    @TenantScoped
    public ApiKeyView updatePolicy(UUID keyId, long expectedVersion, KeyPolicyDraft d) {
        ApiKey key = mutableKey(keyId);
        if (key.getVersion() != expectedVersion) {
            throw new StaleVersionException(expectedVersion, key.getVersion());
        }
        String name = d.name() == null ? "" : d.name().strip();
        if (name.isEmpty() || name.length() > 100) {
            throw new IllegalArgumentException("Key name must be 1-100 characters");
        }
        if (d.rateLimitRpm() < 1 || d.rateLimitRpm() > 10_000 || d.rateLimitTpm() < 100 || d.rateLimitTpm() > 10_000_000) {
            throw new IllegalArgumentException("Limits: 1-10,000 requests/min and 100-10,000,000 tokens/min");
        }
        if (d.expiresAt() != null && !d.expiresAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("Expiry must be in the future (revoke the key to stop it now)");
        }
        key.updatePolicy(name, d.rateLimitRpm(), d.rateLimitTpm(), d.expiresAt(), models(d.allowedModels()), networks(d.ipAllowlist()));
        apiKeys.saveAndFlush(key);
        directory.evictKey(key.getKeyHash());
        rateLimiter.invalidate(key.getId());
        return view(key);
    }

    /**
     * Zero-downtime rotation: a new key with the same project, limits and restrictions starts working now; the old
     * one keeps working for {@code graceHours} (0 = revoked immediately) so every client can be switched over.
     */
    @TenantScoped
    public RotatedApiKey rotateKey(UUID keyId, int graceHours) {
        if (graceHours < 0 || graceHours > MAX_GRACE_HOURS) {
            throw new IllegalArgumentException("Grace period must be 0-" + MAX_GRACE_HOURS + " hours");
        }
        ApiKey old = mutableKey(keyId);
        if (old.getExpiresAt() != null && !clock.instant().isBefore(old.getExpiresAt())) {
            throw new ResourceConflictException("The key has already expired; create a new key instead");
        }
        long active = apiKeys.findAllByOrderByCreatedAtDesc().stream().filter(ApiKey::isActive).count();
        if (graceHours > 0 && active >= MAX_ACTIVE_KEYS) {
            throw new ResourceConflictException("A workspace can have at most " + MAX_ACTIVE_KEYS + " active keys; rotate with no grace period");
        }
        String raw = ApiKeys.generate();
        ApiKey next = apiKeys.save(old.successor(ApiKeys.displayPrefix(raw), ApiKeys.sha256Hex(raw)));
        if (graceHours == 0) {
            old.revoke();
        } else {
            old.expireAt(clock.instant().plus(Duration.ofHours(graceHours)));
        }
        apiKeys.flush();
        directory.evictKey(old.getKeyHash());
        rateLimiter.invalidate(old.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", next.getName());
        data.put("old_key_prefix", old.getKeyPrefix());
        data.put("new_key_prefix", next.getKeyPrefix());
        data.put("old_key_valid_until", old.isActive() ? String.valueOf(old.getExpiresAt()) : "revoked");
        outbox.publish(WebhookEvents.KEY_ROTATED, data);
        return new RotatedApiKey(view(next), raw, view(old));
    }

    private ApiKey mutableKey(UUID keyId) {
        ApiKey key = apiKeys.findById(keyId).orElseThrow(() -> new NoSuchElementException("API key not found"));
        if (PUBLIC_DEMO_PREFIX.equals(key.getKeyPrefix())) {
            throw new IllegalArgumentException("The public demo key is shared by every visitor and cannot be changed");
        }
        if (!key.isActive()) {
            throw new IllegalArgumentException("A revoked key cannot be changed");
        }
        return key;
    }

    /** Every allowed model must be in this workspace's catalog (case-insensitive); stored comma-separated. */
    private String models(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return null;
        }
        Set<String> catalog = providers.findAllByOrderByPriorityOrderAsc().stream()
                .map(p -> p.getModelName().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Set<String> out = new LinkedHashSet<>();
        for (String m : requested) {
            String model = m == null ? "" : m.strip();
            if (!catalog.contains(model.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("'" + model + "' is not in this workspace's model catalog");
            }
            out.add(model);
        }
        if (out.size() > MAX_POLICY_ENTRIES) {
            throw new IllegalArgumentException("At most " + MAX_POLICY_ENTRIES + " allowed models");
        }
        return String.join(",", out);
    }

    private static String networks(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String n : requested) {
            if (n != null && !n.isBlank()) {
                out.add(Cidr.parse(n).text()); // throws 400 with the offending entry
            }
        }
        if (out.size() > MAX_POLICY_ENTRIES) {
            throw new IllegalArgumentException("At most " + MAX_POLICY_ENTRIES + " IP ranges");
        }
        return out.isEmpty() ? null : String.join(",", out);
    }

    /** Moves an active key to another project ({@code null} = unassigned). Spend history stays where it was charged. */
    @TenantScoped
    public ApiKeyView assignKeyToProject(UUID keyId, UUID projectId) {
        ApiKey key = apiKeys.findById(keyId).orElseThrow(() -> new NoSuchElementException("API key not found"));
        if (!key.isActive()) {
            throw new IllegalArgumentException("A revoked key cannot be reassigned");
        }
        if (projectId != null) {
            requireActiveProject(projectId);
        }
        key.assignProject(projectId);
        directory.evictKey(key.getKeyHash()); // the cached principal carries the old project
        return view(key);
    }

    private void requireActiveProject(UUID projectId) {
        Project project = projects.findById(projectId).orElseThrow(() -> new NoSuchElementException("Project not found"));
        if (!project.isActive()) {
            throw new ProjectArchivedException(project.getName());
        }
    }

    @TenantScoped(readOnly = true)
    public List<ProviderView> listProviders() {
        return providers.findAllByOrderByPriorityOrderAsc().stream().map(this::view).toList();
    }

    /** Re-ranks the fallback chain; {@code orderedIds} must be a permutation of the tenant's providers. */
    @TenantScoped
    public List<ProviderView> reorderProviders(List<UUID> orderedIds) {
        List<ProviderConfig> all = providers.findAllByOrderByPriorityOrderAsc();
        Set<UUID> requested = new HashSet<>(orderedIds);
        Set<UUID> existing = all.stream().map(ProviderConfig::getId).collect(Collectors.toSet());
        if (requested.size() != orderedIds.size() || !requested.equals(existing)) {
            throw new IllegalArgumentException("Order must list every provider exactly once");
        }
        Map<UUID, ProviderConfig> byId = all.stream().collect(Collectors.toMap(ProviderConfig::getId, Function.identity()));
        for (int i = 0; i < orderedIds.size(); i++) {
            byId.get(orderedIds.get(i)).setPriorityOrder(i + 1); // unique(tenant, priority) is DEFERRABLE
        }
        return orderedIds.stream().map(byId::get).map(this::view).toList();
    }

    @TenantScoped
    public ProviderView setProviderEnabled(UUID providerId, boolean enabled) {
        ProviderConfig cfg = providers.findById(providerId).orElseThrow(() -> new NoSuchElementException("Provider not found"));
        if (!enabled && providers.findByEnabledTrueOrderByPriorityOrderAsc().stream()
                .allMatch(p -> p.getId().equals(providerId))) {
            throw new IllegalArgumentException("At least one provider must stay enabled");
        }
        cfg.setEnabled(enabled);
        return view(cfg);
    }

    public TopUpResult topUp(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(maxTopUp) > 0) {
            throw new IllegalArgumentException("Top-up amount must be between $0 and $" + maxTopUp.toPlainString());
        }
        String requestId = "topup_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        BigDecimal balance = ledger.topUp(requestId, amount, "Manual top-up");
        return new TopUpResult(requestId, CostCalculator.normalize(amount), balance);
    }

    /** Adds a model at the end of the fallback chain. MOCK_LLM is internal and cannot be added. */
    @TenantScoped
    public ProviderView addProvider(String provider, String modelName, BigDecimal inputCost, BigDecimal outputCost) {
        ProviderType type = providerType(provider);
        String model = modelName(modelName);
        validatePrices(inputCost, outputCost);
        List<ProviderConfig> all = providers.findAllByOrderByPriorityOrderAsc();
        if (all.size() >= MAX_PROVIDERS) {
            throw new ResourceConflictException("A fallback chain can have at most " + MAX_PROVIDERS + " models");
        }
        if (providers.existsByProviderAndModelNameIgnoreCase(type, model)) {
            throw new ResourceConflictException(type + " " + model + " is already in the chain");
        }
        int priority = all.stream().mapToInt(ProviderConfig::getPriorityOrder).max().orElse(0) + 1;
        ProviderConfig cfg = providers.saveAndFlush(new ProviderConfig(TenantContext.require(), type, model, priority,
                CostCalculator.normalize(inputCost), CostCalculator.normalize(outputCost)));
        return view(cfg);
    }

    /** Changes model name and prices. Holds already taken keep their price; the next request uses the new one. */
    @TenantScoped
    public ProviderView updateProvider(UUID providerId, String modelName, BigDecimal inputCost, BigDecimal outputCost) {
        ProviderConfig cfg = providers.findById(providerId).orElseThrow(() -> new NoSuchElementException("Provider not found"));
        String model = modelName(modelName);
        validatePrices(inputCost, outputCost);
        if (providers.existsByProviderAndModelNameIgnoreCaseAndIdNot(cfg.getProvider(), model, providerId)) {
            throw new ResourceConflictException(cfg.getProvider() + " " + model + " is already in the chain");
        }
        cfg.reprice(model, CostCalculator.normalize(inputCost), CostCalculator.normalize(outputCost));
        return view(cfg);
    }

    /** Removes a model and closes the gap in priorities. The last enabled model can never be removed. */
    @TenantScoped
    public void deleteProvider(UUID providerId) {
        List<ProviderConfig> all = providers.findAllByOrderByPriorityOrderAsc();
        ProviderConfig target = all.stream().filter(p -> p.getId().equals(providerId)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Provider not found"));
        boolean otherEnabled = all.stream().anyMatch(p -> !p.getId().equals(providerId) && p.isEnabled());
        if (!otherEnabled) {
            throw new ResourceConflictException("At least one enabled model must remain in the chain");
        }
        providers.delete(target);
        providers.flush();
        int priority = 1;
        for (ProviderConfig p : all) {
            if (!p.getId().equals(providerId)) {
                p.setPriorityOrder(priority++); // unique(tenant, priority) is DEFERRABLE
            }
        }
    }

    private static ProviderType providerType(String provider) {
        try {
            ProviderType type = ProviderType.valueOf(provider == null ? "" : provider.strip().toUpperCase(java.util.Locale.ROOT));
            if (type == ProviderType.MOCK_LLM) {
                throw new IllegalArgumentException("MOCK_LLM is internal");
            }
            return type;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Provider must be one of OPENAI, ANTHROPIC, GEMINI");
        }
    }

    private static String modelName(String modelName) {
        String m = modelName == null ? "" : modelName.strip();
        if (!m.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{1,79}")) {
            throw new IllegalArgumentException("Model name must be 2-80 characters: letters, digits and . _ : / -");
        }
        return m;
    }

    private static void validatePrices(BigDecimal input, BigDecimal output) {
        if (input == null || output == null || input.signum() < 0 || output.signum() <= 0
                || input.compareTo(MAX_PRICE_PER_1M) > 0 || output.compareTo(MAX_PRICE_PER_1M) > 0) {
            throw new IllegalArgumentException("Prices are USD per 1M tokens: input >= 0, output > 0, both at most $1,000");
        }
    }

    /** Enabled providers in priority order, as routing targets. */
    @TenantScoped(readOnly = true)
    public List<RouteTarget> routeChain() {
        return providers.findByEnabledTrueOrderByPriorityOrderAsc().stream()
                .map(p -> new RouteTarget(p.getProvider(), p.getModelName(), p.getPriorityOrder(),
                        p.getInputCostPer1m(), p.getOutputCostPer1m()))
                .toList();
    }

    private ApiKeyView view(ApiKey k) {
        Instant now = clock.instant();
        String status = !k.isActive() ? "REVOKED" : k.getExpiresAt() != null && !now.isBefore(k.getExpiresAt()) ? "EXPIRED" : "ACTIVE";
        return new ApiKeyView(k.getId(), k.getName(), k.getKeyPrefix(), k.getRateLimitRpm(), k.getRateLimitTpm(),
                k.isActive(), k.getLastUsedAt(), k.getCreatedAt(), k.getProjectId(), status, k.getExpiresAt(),
                KeyPolicy.split(k.getAllowedModels()), KeyPolicy.split(k.getIpAllowlist()), k.getRotatedFrom(), k.getVersion());
    }

    private ProviderView view(ProviderConfig p) {
        return new ProviderView(p.getId(), p.getProvider().name(), p.getModelName(), p.getPriorityOrder(),
                p.getInputCostPer1m(), p.getOutputCostPer1m(), p.isEnabled(), router.isLive(p.getProvider()));
    }
}
