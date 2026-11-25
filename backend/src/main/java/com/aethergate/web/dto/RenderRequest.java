package com.aethergate.web.dto;

import java.util.Map;

public record RenderRequest(Integer version, Map<String, String> variables) {}
