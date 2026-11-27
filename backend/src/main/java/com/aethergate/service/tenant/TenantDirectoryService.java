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
import com.aethergate.domain.model.MemberRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
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
    /** Every workspace a user can open (own first, then memberships), 30 s; evicted on membership changes. */
    private final Cache<UUID, List<WorkspaceAccess>> accessCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30)).maximumSize(50_000).build();
    private final JdbcTemplate jdbc;

    public TenantDirectoryService(TenantRepository tenants, ApiKeyRepository apiKeys, ProviderConfigRepository providers,
                                  MeteringLedgerService ledger, PlatformTransactionManager txManager, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                                key.getRateLimitRpm(), key.getRateLimitTpm(), key.getProjectId())
                                .withPolicy(KeyPolicy.of(key.getExpiresAt(), key.getAllowedModels(), key.getIpAllowlist(),
                                        key.getMonthlyBudgetUsd()))))));
    }

    /** Returns the user's default workspace, creating one (with welcome credit and default routing) on first login. */
    public TenantRef resolveOrProvision(UUID userId, String email) {
        return access(userId, email, null).tenant();
    }

    /**
     * The workspace the user acts in: {@code requested} (from the console's X-Workspace-Id header) when given,
     * else their own workspace, else the first one they joined; a user with neither gets a new workspace.
     */
    public WorkspaceAccess access(UUID userId, String email, UUID requested) {
        List<WorkspaceAccess> all = workspaces(userId, email);
        if (requested == null) {
            return all.getFirst();
        }
        return all.stream().filter(a -> a.tenantId().equals(requested)).findFirst()
                .orElseThrow(WorkspaceAccessDeniedException::new);
    }

    /** Own workspace first (OWNER), then active memberships in join order. Never empty: provisions on first login. */
    public List<WorkspaceAccess> workspaces(UUID userId, String email) {
        List<WorkspaceAccess> cached = accessCache.getIfPresent(userId);
        if (cached != null) {
            return cached;
        }
        List<WorkspaceAccess> found = load(userId);
        if (found.isEmpty()) {
            provision(userId, email);
            found = load(userId);
        }
        List<WorkspaceAccess> result = List.copyOf(found);
        accessCache.put(userId, result);
        return result;
    }

    private List<WorkspaceAccess> load(UUID userId) {
        List<WorkspaceAccess> out = new ArrayList<>();
        readTx.executeWithoutResult(status -> {
            tenants.findByOwnerUserId(userId).ifPresent(t -> out.add(new WorkspaceAccess(toRef(t), MemberRole.OWNER, true)));
            // privileged lookup (no tenant is known yet), like the API-key directory: routing metadata only
            out.addAll(jdbc.query("""
                    select t.id, t.name, t.slug, t.plan_tier, t.is_demo, m.role from tenant_members m
                    join tenants t on t.id = m.tenant_id
                    where m.user_id = ? and m.status = 'ACTIVE' and t.owner_user_id <> ?
                    order by m.joined_at""", (rs, i) -> new WorkspaceAccess(new TenantRef(rs.getObject("id", UUID.class),
                    rs.getString("name"), rs.getString("slug"), rs.getString("plan_tier"), rs.getBoolean("is_demo")),
                    MemberRole.valueOf(rs.getString("role")), false), userId, userId));
        });
        return out;
    }

    /** A pending invitation found by its token hash, across all tenants (the invitee is not a member yet). */
    public record Invitation(UUID memberId, UUID tenantId, String tenantName, String email, MemberRole role,
                             Instant expiresAt, UUID ownerUserId) {}

    public Optional<Invitation> findInvitation(String tokenHash) {
        return readTx.execute(status -> jdbc.query("""
                select m.id, m.tenant_id, t.name, m.email, m.role, m.invite_expires_at, t.owner_user_id
                from tenant_members m join tenants t on t.id = m.tenant_id
                where m.invite_token_hash = ? and m.status = 'INVITED'""", (rs, i) -> new Invitation(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                MemberRole.valueOf(rs.getString(5)), rs.getTimestamp(6).toInstant(), rs.getObject(7, UUID.class)),
                tokenHash).stream().findFirst());
    }

    public Optional<TenantRef> findBySlug(String slug) {
        return readTx.execute(status -> tenants.findBySlug(slug).map(TenantDirectoryService::toRef));
    }

    public Optional<UUID> ownerOf(String slug) {
        return readTx.execute(status -> tenants.findBySlug(slug).map(Tenant::getOwnerUserId));
    }

    /** Drops the cached workspaces of a user (role change, removal, join) so the next request sees it. */
    public void evictUser(UUID userId) {
        if (userId != null) {
            accessCache.invalidate(userId);
        }
    }

    /** A workspace was renamed: every member's cached copy carries the old name. */
    public void evictAllWorkspaces() {
        accessCache.invalidateAll();
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
        accessCache.invalidate(userId);
    }

    public void evictKey(String keyHash) {
        keyCache.invalidate(keyHash);
    }

    private void provision(UUID userId, String email) {
        try {
            tx.execute(status -> {
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
            if (readTx.execute(status -> tenants.findByOwnerUserId(userId)).isEmpty()) {
                throw raced;
            }
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static TenantRef toRef(Tenant t) {
        return new TenantRef(t.getId(), t.getName(), t.getSlug(), t.getPlanTier(), t.isDemo());
    }
}
