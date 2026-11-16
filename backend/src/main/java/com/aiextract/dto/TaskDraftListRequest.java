package com.aiextract.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record TaskDraftListRequest(
        @NotNull @Size(min = 1, max = 3) List<@Valid TaskDraftRequest> drafts
) {
}
