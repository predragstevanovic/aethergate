package com.aethergate.service.tenant;

import com.aethergate.config.AetherGateProperties;
import com.aethergate.config.TenantContext;
import com.aethergate.config.TenantScoped;
import com.aethergate.domain.model.ApiKey;
import com.aethergate.domain.model.Project;
import com.aethergate.domain.model.ProviderConfig;
import com.aethergate.domain.repository.ApiKeyRepository;
import com.aethergate.domain.repository.ProjectRepository;
import com.aethergate.domain.repository.ProviderConfigRepository;
import com.aethergate.service.llm.LlmRouterService;
import com.aethergate.service.llm.RouteTarget;
import com.aethergate.service.metering.CostCalculator;
import com.aethergate.service.metering.MeteringLedgerService;
import com.aethergate.service.project.ProjectArchivedException;
import com.aethergate.service.ratelimit.RateLimiterService;
import com.aethergate.service.tenant.AdminViews.ApiKeyView;
import com.aethergate.service.tenant.AdminViews.CreatedApiKey;
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
    static final String PUBLIC_DEMO_PREFIX = "ag_live_demo";

    private final ApiKeyRepository apiKeys;
    private final ProviderConfigRepository providers;
    private final ProjectRepository projects;
    private final MeteringLedgerService ledger;
    private final TenantDirectoryService directory;
    private final RateLimiterService rateLimiter;
    private final LlmRouterService router;
    private final BigDecimal maxTopUp;

    @SuppressWarnings("java:S107")
    public TenantAdminService(ApiKeyRepository apiKeys, ProviderConfigRepository providers, ProjectRepository projects,
                              MeteringLedgerService ledger, TenantDirectoryService directory, RateLimiterService rateLimiter,
                              LlmRouterService router, AetherGateProperties properties) {
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
        return apiKeys.findAllByOrderByCreatedAtDesc().stream().map(TenantAdminService::view).toList();
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

    /** Enabled providers in priority order, as routing targets. */
    @TenantScoped(readOnly = true)
    public List<RouteTarget> routeChain() {
        return providers.findByEnabledTrueOrderByPriorityOrderAsc().stream()
                .map(p -> new RouteTarget(p.getProvider(), p.getModelName(), p.getPriorityOrder(),
                        p.getInputCostPer1m(), p.getOutputCostPer1m()))
                .toList();
    }

    private static ApiKeyView view(ApiKey k) {
        return new ApiKeyView(k.getId(), k.getName(), k.getKeyPrefix(), k.getRateLimitRpm(), k.getRateLimitTpm(),
                k.isActive(), k.getLastUsedAt(), k.getCreatedAt(), k.getProjectId());
    }

    private ProviderView view(ProviderConfig p) {
        return new ProviderView(p.getId(), p.getProvider().name(), p.getModelName(), p.getPriorityOrder(),
                p.getInputCostPer1m(), p.getOutputCostPer1m(), p.isEnabled(), router.isLive(p.getProvider()));
    }
}
