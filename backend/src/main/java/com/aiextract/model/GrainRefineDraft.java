package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 颗粒纠偏草稿 —— 属主用自然语言描述「哪里不对」，AI 落成五段结构化的修改。
 *
 * <p>base_* 是开启纠偏时的颗粒快照，draft_* 是 AI 给出的新版本。
 * apply 时把当前颗粒五段与 base_* 逐段比对：不一致说明中途有人改过，返回 409 而不是静默覆盖。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-18
 */
@Entity
@Table(name = "grain_refine_draft")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrainRefineDraft {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_APPLIED = "applied";
    public static final String STATUS_DISCARDED = "discarded";
    public static final String STATUS_REVERTED = "reverted";

    public static final String EMBEDDING_PENDING = "pending";
    public static final String EMBEDDING_READY = "ready";
    public static final String EMBEDDING_FAILED = "failed";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "grain_id", nullable = false)
    private UUID grainId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "base_scene", columnDefinition = "TEXT")
    private String baseScene;
    @Column(name = "base_thought", columnDefinition = "TEXT")
    private String baseThought;
    @Column(name = "base_script", columnDefinition = "TEXT")
    private String baseScript;
    @Column(name = "base_mistake", columnDefinition = "TEXT")
    private String baseMistake;
    @Column(name = "base_condition", columnDefinition = "TEXT")
    private String baseCondition;

    @Column(name = "draft_scene", columnDefinition = "TEXT")
    private String draftScene;
    @Column(name = "draft_thought", columnDefinition = "TEXT")
    private String draftThought;
    @Column(name = "draft_script", columnDefinition = "TEXT")
    private String draftScript;
    @Column(name = "draft_mistake", columnDefinition = "TEXT")
    private String draftMistake;
    @Column(name = "draft_condition", columnDefinition = "TEXT")
    private String draftCondition;

    /** ["expertThought","standardScript"] —— 服务端按 base/draft 实际差异计算，不信任模型 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changed_fields", nullable = false, columnDefinition = "JSONB")
    private String changedFields;

    /** [{role,content}] 多轮纠偏对话 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transcript", columnDefinition = "JSONB")
    private String transcript;

    /** pending → ready / failed；重建向量是异步的，前端据此点亮「再问一次」 */
    @Column(name = "embedding_status", nullable = false, length = 16)
    private String embeddingStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;
}
