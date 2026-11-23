package com.aethergate.web.dto;

import java.util.UUID;

/** Moves an API key to a project; {@code projectId = null} makes it unassigned. */
public record AssignKeyProjectRequest(UUID projectId) {}
