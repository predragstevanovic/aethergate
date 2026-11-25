package com.aethergate.web;

import com.aethergate.config.TenantContext;
import com.aethergate.domain.model.MemberRole;
import com.aethergate.service.team.AuditService;
import com.aethergate.service.tenant.InsufficientRoleException;
import com.aethergate.service.tenant.WorkspaceAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Server-side RBAC for the console API plus the audit trail.
 *
 * <ol>
 *   <li>{@code preHandle}: resolves the caller's workspace and role and checks it against {@link RequiresRole}
 *       (default: GET = VIEWER, anything else = ADMIN). Refusals are audited too.</li>
 *   <li>{@code afterCompletion}: every write (except playground runs) is recorded with actor, role, route,
 *       target id and resulting HTTP status in the append-only audit log.</li>
 * </ol>
 * Hiding buttons in the UI is a convenience; this is the enforcement.
 */
@Component
public class RoleInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RoleInterceptor.class);

    private final CurrentTenant currentTenant;
    private final AuditService audit;

    public RoleInterceptor(CurrentTenant currentTenant, AuditService audit) {
        this.currentTenant = currentTenant;
        this.audit = audit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || method.hasMethodAnnotation(WorkspaceAgnostic.class)) {
            return true;
        }
        Jwt jwt = jwt();
        if (jwt == null) {
            return true; // public endpoint (auth helpers); Spring Security already decided
        }
        WorkspaceAccess access = currentTenant.access(jwt);
        MemberRole required = required(method, request.getMethod());
        if (!access.role().atLeast(required)) {
            record(request, jwt, access, 403);
            throw new InsufficientRoleException(required, access.role());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!(handler instanceof HandlerMethod method) || method.hasMethodAnnotation(WorkspaceAgnostic.class)
                || isRead(request.getMethod()) || request.getRequestURI().startsWith("/api/v1/playground")) {
            return;
        }
        Jwt jwt = jwt();
        if (jwt != null && request.getAttribute(CurrentTenant.ATTRIBUTE) instanceof WorkspaceAccess access) {
            record(request, jwt, access, response.getStatus());
        }
    }

    static MemberRole required(HandlerMethod method, String httpMethod) {
        RequiresRole annotation = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiresRole.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresRole.class);
        }
        if (annotation != null) {
            return annotation.value();
        }
        return isRead(httpMethod) ? MemberRole.VIEWER : MemberRole.ADMIN;
    }

    private void record(HttpServletRequest request, Jwt jwt, WorkspaceAccess access, int status) {
        try {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = (pattern == null ? request.getRequestURI() : pattern.toString()).replaceFirst("^/api/v1", "");
            Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            String target = vars instanceof Map<?, ?> m && m.get("id") != null ? m.get("id").toString() : null;
            TenantContext.runAs(access.tenantId(), () -> audit.record(UUID.fromString(jwt.getSubject()),
                    jwt.getClaimAsString("email"), access.role().name(), request.getMethod() + " " + route, target, status));
        } catch (RuntimeException e) {
            log.warn("Audit write failed: {}", e.getMessage()); // never turn a successful change into an error
        }
    }

    private static boolean isRead(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    private static Jwt jwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Jwt jwt ? jwt : null;
    }
}
