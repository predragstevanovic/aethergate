package com.aethergate.service.tenant;

import com.aethergate.config.TenantContext;
import com.aethergate.domain.model.ProviderConfig;
import com.aethergate.domain.model.ProviderType;
import com.aethergate.domain.model.Tenant;
import com.aethergate.domain.repository.ApiKeyRepository;
import com.aethergate.domain.repository.ProviderConfigRepository;
import com.aethergate.domain.repository.TenantRepository;
import com.aethergate.service.metering.MeteringLedgerService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Privileged directory lookups that must happen before a tenant is known: resolving an API key
 * hash or a Supabase user id to a tenant, and provisioning a workspace on first login. These
 * touch only routing metadata; tenant-owned data is always read through {@code @TenantScoped}.
 */
@Service
public class TenantDirectoryService {

    private static final BigDecimal WELCOME_CREDIT = new BigDecimal("100.00000000");
    private static final BigDecimal DEFAULT_BUDGET = new BigDecimal("500.00000000");

    private final TenantRepository tenants;
    private final ApiKeyRepository apiKeys;
    private final ProviderConfigRepository providers;
    private final MeteringLedgerService ledger;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    private final Cache<String, Optional<GatewayPrincipal>> keyCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30)).maximumSize(50_000).build();
    private final Cache<UUID, TenantRef> ownerCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60)).maximumSize(50_000).build();

    public TenantDirectoryService(TenantRepository tenants, ApiKeyRepository apiKeys, ProviderConfigRepository providers,
                                  MeteringLedgerService ledger, PlatformTransactionManager txManager) {
        this.tenants = tenants;
        this.apiKeys = apiKeys;
        this.providers = providers;
        this.ledger = ledger;
        this.tx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    /** Resolves a raw {@code ag_live_...} key. Cached for 30s (positive and negative) per instance. */
    public Optional<GatewayPrincipal> authenticateApiKey(String rawKey) {
        if (!ApiKeys.looksValid(rawKey)) {
            return Optional.empty();
        }
        String hash = ApiKeys.sha256Hex(rawKey);
        return keyCache.get(hash, h -> readTx.execute(status -> apiKeys.findByKeyHashAndActiveTrue(h)
                .flatMap(key -> tenants.findById(key.getTenantId())
                        .map(t -> GatewayPrincipal.forApiKey(t.getId(), t.getName(), key.getId(), key.getKeyPrefix(),
                                key.getRateLimitRpm(), key.getRateLimitTpm())))));
    }

    /** Returns the user's workspace, creating one (with welcome credit and default routing) on first login. */
    public TenantRef resolveOrProvision(UUID userId, String email) {
        TenantRef cached = ownerCache.getIfPresent(userId);
        if (cached != null) {
            return cached;
        }
        TenantRef ref = readTx.execute(status -> tenants.findByOwnerUserId(userId).map(TenantDirectoryService::toRef))
                .orElseGet(() -> provision(userId, email));
        ownerCache.put(userId, ref);
        return ref;
    }

    public Optional<TenantRef> findBySlug(String slug) {
        return readTx.execute(status -> tenants.findBySlug(slug).map(TenantDirectoryService::toRef));
    }

    public Optional<UUID> ownerOf(String slug) {
        return readTx.execute(status -> tenants.findBySlug(slug).map(Tenant::getOwnerUserId));
    }

    /** Points a (demo) tenant at a Supabase Auth user. Owner-level maintenance, used by the demo login. */
    public void bindOwner(String slug, UUID userId) {
        tx.executeWithoutResult(status -> tenants.findBySlug(slug).ifPresent(t -> {
            if (!t.getOwnerUserId().equals(userId)) {
                tenants.findByOwnerUserId(userId).filter(other -> !other.getId().equals(t.getId()))
                        .ifPresent(other -> {
                            throw new IllegalStateException("User already owns another workspace");
                        });
                tenants.rebindOwner(t.getId(), userId);
            }
        }));
        ownerCache.invalidate(userId);
    }

    public void evictKey(String keyHash) {
        keyCache.invalidate(keyHash);
    }

    private TenantRef provision(UUID userId, String email) {
        try {
            return tx.execute(status -> {
                String local = email == null || email.indexOf('@') < 1 ? "workspace" : email.substring(0, email.indexOf('@'));
                String base = local.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
                String slug = (base.isEmpty() ? "workspace" : base) + "-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt()).substring(0, 4);
                String name = Character.toUpperCase(local.charAt(0)) + local.substring(1) + "'s Workspace";
                Tenant tenant = tenants.saveAndFlush(new Tenant(UUID.randomUUID(), userId, truncate(name, 120), slug,
                        BigDecimal.ZERO.setScale(8), DEFAULT_BUDGET));
                providers.saveAllAndFlush(List.of(
                        new ProviderConfig(tenant.getId(), ProviderType.OPENAI, "gpt-4o-mini", 1,
                                new BigDecimal("0.15"), new BigDecimal("0.60")),
                        new ProviderConfig(tenant.getId(), ProviderType.ANTHROPIC, "claude-haiku-4-5", 2,
                                new BigDecimal("1.00"), new BigDecimal("5.00"))));
                // Last step: the credit runs tenant-scoped (SET LOCAL ROLE) inside this same transaction.
                TenantContext.runAs(tenant.getId(), () -> ledger.topUp("topup_welcome_credit", WELCOME_CREDIT, "Welcome credit"));
                return toRef(tenant);
            });
        } catch (DataIntegrityViolationException raced) {
            // Two first-logins at once: the unique owner index let exactly one win.
            return readTx.execute(status -> tenants.findByOwnerUserId(userId).map(TenantDirectoryService::toRef))
                    .orElseThrow(() -> raced);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static TenantRef toRef(Tenant t) {
        return new TenantRef(t.getId(), t.getName(), t.getSlug(), t.getPlanTier(), t.isDemo());
    }
}
