package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 技能参考资料（references/*.md）。正文按需懒加载，不进列表与目录。
 *
 * @since 2026-11-18
 */
@Entity
@Table(name = "agent_skill_resource")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSkillResource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "skill_id", nullable = false, updatable = false)
    private UUID skillId;

    @Column(name = "path", nullable = false, length = 255)
    private String path;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
