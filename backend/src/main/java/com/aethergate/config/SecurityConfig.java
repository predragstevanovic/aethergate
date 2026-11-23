package com.aethergate.config;

import com.aethergate.service.tenant.TenantDirectoryService;
import com.aethergate.web.security.ApiKeyAuthenticationFilter;
import com.aethergate.web.security.ProblemAuthenticationEntryPoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Two stateless filter chains:
 * <ul>
 *   <li>{@code /v1/**}: the OpenAI-compatible gateway, authenticated by tenant API key.</li>
 *   <li>{@code /api/**}: the management console, authenticated by Supabase Auth JWTs
 *       (ES256, verified against the project's JWKS, issuer and audience checked).</li>
 * </ul>
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain gatewayChain(HttpSecurity http, TenantDirectoryService directory, ObjectMapper json) throws Exception {
        return http
                .securityMatcher("/v1/**")
                .headers(SecurityConfig::eagerHeaders)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(gatewayCors()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .addFilterBefore(new ApiKeyAuthenticationFilter(directory), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().hasRole("GATEWAY"))
                .exceptionHandling(e -> e.authenticationEntryPoint(new ProblemAuthenticationEntryPoint(json,
                        "Provide a valid API key via X-API-Key or Authorization: Bearer ag_live_...")))
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain managementChain(HttpSecurity http, ObjectMapper json, AetherGateProperties props) throws Exception {
        ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint(json,
                "Sign in with Supabase Auth and send the access token as a Bearer token");
        return http
                .headers(SecurityConfig::eagerHeaders)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(consoleCors(props)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/demo-session").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/config").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> {}).authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder supabaseJwtDecoder(AetherGateProperties props) {
        String issuer = props.supabase().url() + "/auth/v1";
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/.well-known/jwks.json")
                .jwsAlgorithms(algs -> {
                    algs.add(SignatureAlgorithm.ES256);
                    algs.add(SignatureAlgorithm.RS256);
                })
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audience("authenticated")));
        return decoder;
    }

    /**
     * Security headers are written before the handler runs, not after: a streaming body is
     * written on another thread, and headers added after it started would race with it.
     */
    private static void eagerHeaders(HeadersConfigurer<HttpSecurity> headers) {
        headers.addObjectPostProcessor(new ObjectPostProcessor<HeaderWriterFilter>() {
            @Override
            public <O extends HeaderWriterFilter> O postProcess(O filter) {
                filter.setShouldWriteHeadersEagerly(true);
                return filter;
            }
        });
    }

    static OAuth2TokenValidator<Jwt> audience(String expected) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(expected)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required audience is missing", null));
    }

    private static CorsConfigurationSource consoleCors(AetherGateProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        // patterns, so local dev servers on any port (Vite falls back to 5174, 5175, ...) are accepted
        cfg.setAllowedOriginPatterns(props.cors().allowedOrigins().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Simulate-Provider-Failure"));
        cfg.setExposedHeaders(List.of("X-Request-Id", "X-Cache", "Retry-After"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    /** The gateway is server-to-server, but browsers calling it directly get permissive CORS (key-based auth, no cookies). */
    private static CorsConfigurationSource gatewayCors() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(List.of("*"));
        cfg.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-API-Key", "X-Simulate-Provider-Failure"));
        cfg.setExposedHeaders(List.of("X-Request-Id", "X-Cache", "Retry-After", "X-RateLimit-Remaining-Requests",
                "X-RateLimit-Remaining-Tokens"));
        cfg.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
