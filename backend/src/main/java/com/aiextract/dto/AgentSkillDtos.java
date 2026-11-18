package com.aiextract.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 模型技能管理端 DTO。
 */
public final class AgentSkillDtos {

    private AgentSkillDtos() {
    }

    public record ResourceInput(String path, String content) {
    }

    public record CreateRequest(String scope, String skillKey, String name, String description,
                                String bodyMd, List<ResourceInput> resources) {
    }

    public record UpdateRequest(String name, String description, String bodyMd,
                                List<ResourceInput> resources, Integer lockVersion) {
    }

    public record ListItem(String skillKey, String name, String source, String effectiveStatus,
                           Integer liveVersion, UUID liveId, Integer draftVersion, UUID draftId,
                           Instant updatedAt, UUID companyId, boolean readOnly) {
    }

    public record ResourceMeta(UUID id, String path, int size) {
    }

    public record Detail(UUID id, String skillKey, String name, String description, String bodyMd,
                         String source, UUID companyId, int version, String status, int lockVersion,
                         boolean readOnly, List<ResourceMeta> resources, Instant updatedAt) {
    }

    public record PreviewRequest(String scope, String skillKey, String name, String description,
                                 String bodyMd, List<ResourceInput> resources) {
    }

    public record PreviewResponse(String catalogBlock, String bodyPreview, int chars, int tokenEstimate) {
    }

    public record VersionItem(UUID id, int version, String status, Instant publishedAt,
                              Instant archivedAt, Instant updatedAt) {
    }

    public record ResourceView(UUID id, String path, String content) {
    }

    public record SettingView(boolean enabled) {
    }

    public record SettingRequest(Boolean enabled) {
    }
}
