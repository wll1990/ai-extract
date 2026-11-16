package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LeadContactRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 50) String phone,
        @Pattern(regexp = "decision_maker|influencer|user|gatekeeper|other",
                message = "非法联系人角色") String role,
        Boolean primary,
        @Size(max = 500) String note
) {
}
