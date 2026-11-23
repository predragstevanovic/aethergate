package com.aethergate.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that touches tenant-owned data. {@link TenantAspect} runs it inside a
 * transaction in which PostgreSQL Row-Level Security is active for the tenant bound in
 * {@link TenantContext}. Forgetting to bind a tenant fails fast instead of leaking data.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface TenantScoped {
    boolean readOnly() default false;
}
