package com.aethergate.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aethergate.config.AetherGateProperties.Provider;
import com.aethergate.service.auth.DemoSessionService;
import com.aethergate.service.auth.DemoSessionService.DemoSession;
import com.aethergate.service.auth.DemoUnavailableException;
import com.aethergate.service.tenant.TenantDirectoryService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Demo login against a stubbed Supabase GoTrue, including the self-healing paths. */
class DemoSessionServiceTest {

    private static final UUID USER = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final String TOKEN = """
            {"access_token":"at","refresh_token":"rt","expires_in":3600,"token_type":"bearer","user":{"id":"%s"}}
            """.formatted(USER);

    private StubHttpServer gotrue;
    private TenantDirectoryService directory;

    @BeforeEach
    void setUp() throws Exception {
        gotrue = new StubHttpServer();
        directory = mock(TenantDirectoryService.class);
    }

    @AfterEach
    void tearDown() {
        gotrue.close();
    }

    private DemoSessionService service(String password) {
        Provider none = new Provider("", "");
        return new DemoSessionService(RestClient.builder(),
                TestProps.with(gotrue.url(), "anon", "service", password, none, none, none), directory);
    }

    private static StubHttpServer.Response json(int status, String body) {
        return new StubHttpServer.Response(status, "application/json", body);
    }

    @Test
    void signsInAndBindsTheDemoTenant() {
        gotrue.on("POST /auth/v1/token", json(200, TOKEN));
        DemoSession s = service("pw").createSession();
        assertThat(s.accessToken()).isEqualTo("at");
        assertThat(s.refreshToken()).isEqualTo("rt");
        assertThat(s.expiresIn()).isEqualTo(3600);
        verify(directory).bindOwner("vortex-ai-labs", USER);
        assertThat(gotrue.requests.getFirst().headers().get("Apikey")).containsExactly("anon");
    }

    @Test
    void resetsPasswordOfKnownDemoUserAfterFailedSignIn() {
        when(directory.ownerOf("vortex-ai-labs")).thenReturn(Optional.of(USER));
        gotrue.on("POST /auth/v1/token", json(400, "{\"error\":\"invalid_grant\"}"), json(200, TOKEN));
        gotrue.on("GET /auth/v1/admin/users/" + USER, json(200, "{}"));
        gotrue.on("PUT /auth/v1/admin/users/" + USER, json(200, "{}"));
        assertThat(service("pw").createSession().accessToken()).isEqualTo("at");
        assertThat(gotrue.requests).anyMatch(r -> r.method().equals("PUT")
                && r.headers().get("Authorization").contains("Bearer service"));
    }

    @Test
    void createsDemoUserWhenOwnerIsStillThePlaceholder() {
        UUID placeholder = UUID.randomUUID();
        when(directory.ownerOf("vortex-ai-labs")).thenReturn(Optional.of(placeholder));
        gotrue.on("POST /auth/v1/token", json(400, "{}"), json(200, TOKEN));
        gotrue.on("GET /auth/v1/admin/users/" + placeholder, json(404, "{}"));
        gotrue.on("POST /auth/v1/admin/users", json(200, "{}"));
        service("pw").createSession();
        assertThat(gotrue.requests).anyMatch(r -> r.method().equals("POST") && r.path().equals("/auth/v1/admin/users")
                && r.body().contains("\"email_confirm\":true"));
    }

    @Test
    void locatesExistingUserByEmailWhenCreateConflicts() {
        UUID existing = UUID.randomUUID();
        when(directory.ownerOf("vortex-ai-labs")).thenReturn(Optional.empty());
        gotrue.on("POST /auth/v1/token", json(400, "{}"), json(200, TOKEN));
        gotrue.on("POST /auth/v1/admin/users", json(422, "{\"msg\":\"already registered\"}"));
        gotrue.on("GET /auth/v1/admin/users", json(200,
                "{\"users\":[{\"id\":\"" + UUID.randomUUID() + "\",\"email\":\"other@x.dev\"},{\"id\":\"" + existing + "\",\"email\":\"DEMO@aethergate.dev\"}]}"));
        gotrue.on("PUT /auth/v1/admin/users/" + existing, json(200, "{}"));
        assertThat(service("pw").createSession().tokenType()).isEqualTo("bearer");
    }

    @Test
    void failsClearlyWhenUnconfiguredOrUnhealable() {
        assertThat(service("").isConfigured()).isFalse();
        assertThatThrownBy(() -> service("").createSession()).isInstanceOf(DemoUnavailableException.class);

        when(directory.ownerOf("vortex-ai-labs")).thenReturn(Optional.empty());
        gotrue.on("POST /auth/v1/token", json(400, "{}"));
        gotrue.on("POST /auth/v1/admin/users", json(200, "{}"));
        assertThatThrownBy(() -> service("pw").createSession())
                .isInstanceOf(DemoUnavailableException.class).hasMessageContaining("could not be signed in");
    }

    @Test
    void upstreamOutageIsReportedAsUnavailable() {
        gotrue.on("POST /auth/v1/token", json(502, "{}"));
        assertThatThrownBy(() -> service("pw").createSession())
                .isInstanceOf(DemoUnavailableException.class).hasMessageContaining("502");
    }

    @Test
    void conflictWithoutLocatableUserFails() {
        when(directory.ownerOf("vortex-ai-labs")).thenReturn(Optional.empty());
        gotrue.on("POST /auth/v1/token", json(400, "{}"));
        gotrue.on("POST /auth/v1/admin/users", json(422, "{}"));
        gotrue.on("GET /auth/v1/admin/users", json(200, "{\"users\":[]}"));
        assertThatThrownBy(() -> service("pw").createSession())
                .isInstanceOf(DemoUnavailableException.class).hasMessageContaining("cannot be located");
    }
}
