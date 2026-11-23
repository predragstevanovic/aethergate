package com.aethergate.web.security;

import com.aethergate.service.tenant.ApiKeys;
import com.aethergate.service.tenant.GatewayPrincipal;
import com.aethergate.service.tenant.TenantDirectoryService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gateway authentication: {@code X-API-Key: ag_live_...} or, for OpenAI SDK compatibility,
 * {@code Authorization: Bearer ag_live_...}. The key is SHA-256 hashed and resolved to a tenant.
 * Unknown keys simply stay unauthenticated; the entry point answers 401 problem+json.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final TenantDirectoryService directory;

    public ApiKeyAuthenticationFilter(TenantDirectoryService directory) {
        this.directory = directory;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String raw = extract(request);
        if (raw != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            directory.authenticateApiKey(raw).ifPresent(p -> {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new GatewayAuthentication(p));
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }

    /** Streaming responses complete on an ASYNC dispatch, which must stay authenticated too. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    static String extract(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = auth.substring(7).trim();
            return token.startsWith(ApiKeys.PREFIX) ? token : null;
        }
        return null;
    }

    /** Authenticated gateway caller. */
    public static final class GatewayAuthentication extends AbstractAuthenticationToken {
        private final transient GatewayPrincipal principal;

        public GatewayAuthentication(GatewayPrincipal principal) {
            super(List.of(new SimpleGrantedAuthority("ROLE_GATEWAY")));
            this.principal = principal;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public GatewayPrincipal getPrincipal() {
            return principal;
        }
    }
}
