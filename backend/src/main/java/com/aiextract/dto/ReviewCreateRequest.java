package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ReviewCreateRequest(
        @NotNull UUID coachSkillId,
        @NotBlank @Size(max = 30000) String transcript,
        @Size(max = 200) String customerName,
        @Size(max = 200) String topic,
        Boolean shareTranscriptWithCoach,
        /** 可选：关联已有客户（线索），用于同一客户的跨次复盘对比 */
        UUID leadId
) {
}
