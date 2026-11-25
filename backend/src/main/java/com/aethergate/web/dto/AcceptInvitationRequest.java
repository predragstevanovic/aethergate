package com.aethergate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptInvitationRequest(@NotBlank @Size(max = 100) String token) {}
