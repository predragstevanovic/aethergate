package com.aethergate.service;

import java.util.UUID;

/** Which prompt template version produced a request's messages (recorded in the request log). */
public record TemplateRef(UUID templateId, int version) {}
