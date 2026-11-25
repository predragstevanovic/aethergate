package com.aethergate.web;

import com.aethergate.domain.model.MemberRole;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Minimum workspace role for a console endpoint. Without it, {@link RoleInterceptor} applies a safe default:
 * reads need VIEWER, every write needs ADMIN. So a new endpoint is admin-only until someone decides otherwise.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresRole {
    MemberRole value();
}
