package com.aethergate.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** role: OWNER | ADMIN | DEVELOPER | VIEWER (only roles below your own, unless you are an Owner). */
public record InviteMemberRequest(@NotBlank @Size(max = 254) String email, @NotBlank String role) {}
