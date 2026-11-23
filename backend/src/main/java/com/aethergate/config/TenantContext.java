package com.aethergate.config;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Carries the active tenant for the current unit of work.
 *
 * <p>Java 21's {@code ScopedValue} is still a preview API, so this uses a ThreadLocal with
 * strict scoping instead: the value is only ever set through {@link #callAs}, which restores
 * the previous value in {@code finally}. With one virtual thread per request this gives the
 * same guarantees (no leaks between requests, no inheritance into foreign pools).
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static <T> T callAs(UUID tenantId, Supplier<T> work) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAs(UUID tenantId, Runnable work) {
        callAs(tenantId, () -> {
            work.run();
            return null;
        });
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        return current().orElseThrow(() -> new IllegalStateException("No tenant bound to the current context"));
    }
}
