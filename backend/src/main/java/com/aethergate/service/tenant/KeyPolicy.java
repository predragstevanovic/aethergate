package com.aethergate.service.tenant;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Restrictions carried by an API key: expiry, the models it may use, the networks it may call from.
 * Empty sets mean "no restriction". Evaluated on every request (the principal itself is cached for 30 s).
 */
public record KeyPolicy(Instant expiresAt, Set<String> allowedModels, List<Cidr> ipAllowlist) {

    public static final KeyPolicy NONE = new KeyPolicy(null, Set.of(), List.of());

    public KeyPolicy {
        allowedModels = Set.copyOf(allowedModels);
        ipAllowlist = List.copyOf(ipAllowlist);
    }

    /** From the comma-separated columns of {@code api_keys}. */
    public static KeyPolicy of(Instant expiresAt, String allowedModelsCsv, String ipAllowlistCsv) {
        return new KeyPolicy(expiresAt,
                split(allowedModelsCsv).stream().map(m -> m.toLowerCase(Locale.ROOT)).collect(Collectors.toSet()),
                split(ipAllowlistCsv).stream().map(Cidr::parse).toList());
    }

    public static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of()
                : Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public boolean expired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean allowsIp(String ip) {
        return ipAllowlist.isEmpty() || ipAllowlist.stream().anyMatch(c -> c.contains(ip));
    }

    public boolean allowsModel(String model) {
        return allowedModels.isEmpty() || (model != null && allowedModels.contains(model.strip().toLowerCase(Locale.ROOT)));
    }

    public boolean restrictsModels() {
        return !allowedModels.isEmpty();
    }
}
