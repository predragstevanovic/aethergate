package com.aethergate.service.auth;

import com.aethergate.config.AetherGateProperties;
import com.aethergate.service.tenant.TenantDirectoryService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * "Continue with Demo Workspace": signs the shared demo user in against Supabase Auth on the
 * server, so the demo password never ships in the frontend bundle.
 *
 * <p>Self-healing: if someone changed the demo user's password (any signed-in user can), the
 * service resets it through the GoTrue admin API with the service-role key and retries. On
 * first use it also creates the user and binds the pre-seeded demo tenant to it.
 */
@Service
public class DemoSessionService {

    private static final Logger log = LoggerFactory.getLogger(DemoSessionService.class);

    public record DemoSession(String accessToken, String refreshToken, long expiresIn, String tokenType) {}

    private final RestClient http;
    private final AetherGateProperties.Supabase supabase;
    private final AetherGateProperties.Demo demo;
    private final TenantDirectoryService directory;
    private final ReentrantLock healLock = new ReentrantLock();

    public DemoSessionService(RestClient.Builder builder, AetherGateProperties properties, TenantDirectoryService directory) {
        this.supabase = properties.supabase();
        this.demo = properties.demo();
        this.directory = directory;
        this.http = builder.baseUrl(supabase.url() + "/auth/v1").build();
    }

    public boolean isConfigured() {
        return !demo.password().isBlank() && !supabase.anonKey().isBlank() && !supabase.serviceRoleKey().isBlank();
    }

    public DemoSession createSession() {
        if (!isConfigured()) {
            throw new DemoUnavailableException("Demo login is not configured on this deployment");
        }
        JsonNode token = signIn().orElseGet(() -> {
            heal();
            return signIn().orElseThrow(() -> new DemoUnavailableException("Demo user could not be signed in"));
        });
        UUID userId = UUID.fromString(token.path("user").path("id").asText());
        directory.bindOwner(demo.tenantSlug(), userId);
        return new DemoSession(token.path("access_token").asText(), token.path("refresh_token").asText(),
                token.path("expires_in").asLong(3600), token.path("token_type").asText("bearer"));
    }

    private Optional<JsonNode> signIn() {
        try {
            return Optional.ofNullable(http.post()
                    .uri("/token?grant_type=password")
                    .header("apikey", supabase.anonKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", demo.email(), "password", demo.password()))
                    .retrieve()
                    .body(JsonNode.class));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                return Optional.empty();
            }
            throw new DemoUnavailableException("Supabase Auth returned " + e.getStatusCode().value());
        }
    }

    /** Ensures the demo user exists with the configured credentials. */
    private void heal() {
        healLock.lock();
        try {
            Optional<UUID> known = directory.ownerOf(demo.tenantSlug()).filter(this::userExists);
            if (known.isPresent()) {
                admin().put().uri("/admin/users/{id}", known.get())
                        .body(Map.of("email", demo.email(), "password", demo.password(), "email_confirm", true))
                        .retrieve().toBodilessEntity();
                log.warn("Demo user credentials were reset");
                return;
            }
            try {
                admin().post().uri("/admin/users")
                        .body(Map.of("email", demo.email(), "password", demo.password(), "email_confirm", true,
                                "user_metadata", Map.of("full_name", "Demo Workspace")))
                        .retrieve().toBodilessEntity();
                log.info("Demo user created");
            } catch (RestClientResponseException e) {
                if (!isConflict(e.getStatusCode())) {
                    throw e;
                }
                UUID existing = findUserIdByEmail(demo.email())
                        .orElseThrow(() -> new DemoUnavailableException("Demo user exists but cannot be located"));
                admin().put().uri("/admin/users/{id}", existing)
                        .body(Map.of("password", demo.password(), "email_confirm", true))
                        .retrieve().toBodilessEntity();
            }
        } finally {
            healLock.unlock();
        }
    }

    private boolean userExists(UUID id) {
        try {
            admin().get().uri("/admin/users/{id}", id).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            return false;
        }
    }

    private Optional<UUID> findUserIdByEmail(String email) {
        JsonNode page = admin().get().uri("/admin/users?page=1&per_page=1000").retrieve().body(JsonNode.class);
        if (page == null) {
            return Optional.empty();
        }
        for (JsonNode user : page.path("users")) {
            if (email.equalsIgnoreCase(user.path("email").asText())) {
                return Optional.of(UUID.fromString(user.path("id").asText()));
            }
        }
        return Optional.empty();
    }

    private static boolean isConflict(HttpStatusCode status) {
        return status.value() == 422 || status.value() == 409 || status.value() == 400;
    }

    private RestClient admin() {
        return http.mutate()
                .defaultHeader("apikey", supabase.serviceRoleKey())
                .defaultHeader("Authorization", "Bearer " + supabase.serviceRoleKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
