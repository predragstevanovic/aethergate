package com.aethergate.config;

import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opens (or joins) a transaction and makes Row-Level Security apply to it:
 *
 * <ol>
 *   <li>{@code set_config('app.current_tenant_id', id, true)} binds the tenant for this
 *       transaction only, which is safe behind a connection pooler.</li>
 *   <li>{@code SET LOCAL ROLE aethergate_app} (issued as {@code set_config('role', ..., true)}) drops the connection's BYPASSRLS / superuser
 *       privileges so the policies are really enforced (Supabase's {@code postgres} role
 *       bypasses RLS otherwise).</li>
 * </ol>
 *
 * Both settings end with the transaction, so a pooled connection is always returned clean.
 */
@Aspect
@Component
public class TenantAspect {

    public static final String APP_ROLE = "aethergate_app";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readWrite;
    private final TransactionTemplate readOnly;

    public TenantAspect(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.readWrite = new TransactionTemplate(txManager);
        this.readOnly = new TransactionTemplate(txManager);
        this.readOnly.setReadOnly(true);
    }

    @Around("@annotation(com.aethergate.config.TenantScoped) || @within(com.aethergate.config.TenantScoped)")
    public Object bindTenant(ProceedingJoinPoint pjp) throws Throwable {
        UUID tenantId = TenantContext.require();
        TenantScoped scope = resolve(pjp);
        TransactionTemplate template = scope != null && scope.readOnly() ? readOnly : readWrite;
        try {
            return template.execute(status -> {
                // one round trip: set_config('role', ..., true) is SET LOCAL ROLE
                jdbc.queryForList("select set_config('app.current_tenant_id', ?, true), set_config('role', ?, true)",
                        tenantId.toString(), APP_ROLE);
                try {
                    return pjp.proceed();
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new CheckedWrapper(t);
                }
            });
        } catch (CheckedWrapper w) {
            throw w.getCause();
        }
    }

    private static TenantScoped resolve(ProceedingJoinPoint pjp) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        TenantScoped onMethod = AnnotatedElementUtils.findMergedAnnotation(signature.getMethod(), TenantScoped.class);
        return onMethod != null
                ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(pjp.getTarget().getClass(), TenantScoped.class);
    }

    private static final class CheckedWrapper extends RuntimeException {
        CheckedWrapper(Throwable cause) {
            super(cause);
        }
    }
}
