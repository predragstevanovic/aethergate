package com.aethergate.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Provider ids in the desired priority order (index 0 = primary). */
public record ReorderProvidersRequest(@NotEmpty @Size(max = 10) List<@NotNull UUID> providerIds) {}
