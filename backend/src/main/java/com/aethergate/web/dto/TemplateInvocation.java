package com.aethergate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** Run a stored prompt template instead of raw messages: {@code id} is the template id or its name. */
public record TemplateInvocation(@NotBlank @Size(max = 80) String id, Integer version, Map<String, String> variables) {}
