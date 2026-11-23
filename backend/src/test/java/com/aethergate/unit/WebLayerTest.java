package com.aethergate.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aethergate.config.SecurityConfig;
import com.aethergate.service.tenant.GatewayPrincipal;
import com.aethergate.service.tenant.TenantDirectoryService;
import com.aethergate.web.security.ApiKeyAuthenticationFilter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class WebLayerTest {

    private final TenantDirectoryService directory = mock(TenantDirectoryService.class);
    private final ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(directory);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void run(MockHttpServletRequest req) throws Exception {
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
    }

    @Test
    void acceptsXApiKeyAndOpenAiStyleBearer() throws Exception {
        GatewayPrincipal p = GatewayPrincipal.forApiKey(UUID.randomUUID(), "T", UUID.randomUUID(), "ag_live_abcd", 60, 1000);
        when(directory.authenticateApiKey("ag_live_key1")).thenReturn(Optional.of(p));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/chat/completions");
        req.addHeader("X-API-Key", " ag_live_key1 ");
        run(req);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(p);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getCredentials()).isEqualTo("");
        assertThat(p.limits().persistent()).isTrue();

        SecurityContextHolder.clearContext();
        when(directory.authenticateApiKey("ag_live_key2")).thenReturn(Optional.empty());
        MockHttpServletRequest bearer = new MockHttpServletRequest("POST", "/v1/chat/completions");
        bearer.addHeader("Authorization", "bearer ag_live_key2");
        run(bearer);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void ignoresNonGatewayCredentials() throws Exception {
        MockHttpServletRequest jwtLike = new MockHttpServletRequest("GET", "/v1/models");
        jwtLike.addHeader("Authorization", "Bearer eyJhbGciOiJFUzI1NiJ9.payload.sig");
        run(jwtLike);
        MockHttpServletRequest blank = new MockHttpServletRequest("GET", "/v1/models");
        blank.addHeader("X-API-Key", "  ");
        blank.addHeader("Authorization", "Basic abc");
        run(blank);
        run(new MockHttpServletRequest("GET", "/v1/models"));
        verifyNoInteractions(directory);
    }

    @Test
    void playgroundPrincipalHasStableNonPersistentLimiterKey() {
        UUID tenant = UUID.randomUUID();
        GatewayPrincipal a = GatewayPrincipal.forPlayground(tenant, "T", 30, 600);
        GatewayPrincipal b = GatewayPrincipal.forPlayground(tenant, "T", 30, 600);
        assertThat(a.limiterKey()).isEqualTo(b.limiterKey());
        assertThat(a.apiKeyId()).isNull();
        assertThat(a.limits().persistent()).isFalse();
    }

    @Test
    void jwtAudienceValidator() throws Exception {
        var method = SecurityConfig.class.getDeclaredMethod("audience", String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        var validator = (org.springframework.security.oauth2.core.OAuth2TokenValidator<Jwt>) method.invoke(null, "authenticated");
        Jwt good = Jwt.withTokenValue("t").header("alg", "ES256").audience(List.of("authenticated"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        Jwt wrong = Jwt.withTokenValue("t").header("alg", "ES256").audience(List.of("anon"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        Jwt none = Jwt.withTokenValue("t").header("alg", "ES256").claim("sub", "x")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(validator.validate(good).hasErrors()).isFalse();
        assertThat(validator.validate(wrong).hasErrors()).isTrue();
        assertThat(validator.validate(none).hasErrors()).isTrue();
    }
}
