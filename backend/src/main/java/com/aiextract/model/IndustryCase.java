package com.aiextract.model;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 行业案例库实体 — 过程型案例（客户画像/推进过程/结果/可复用点）。
 *
 * <p>与知识条目 {@link KnowledgeEntry} 区分：知识是「事实」，案例是「故事」。
 * 案例来自「书 / 已整理案例文档」的结构化抽取，不含从原始对话的因果重建
 * （后者归复盘诊断，不在此实体）。</p>
 *
 * <p>company_id 为 NULL 表示平台公共案例，非 NULL 表示租户私有案例。
 * 无 spaceId，贡献归属用 contributorId。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Entity
@Table(name = "industry_case")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndustryCase {

    /** 案例唯一标识 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属企业 ID，NULL=平台公共案例 */
    @Column(name = "company_id")
    private UUID companyId;

    /** 案例标题 */
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** 客户画像（JSONB：行业/规模/角色） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "customer_profile", columnDefinition = "JSONB")
    private String customerProfile;

    /** 漏斗阶段：线索/首触/需求/方案/异议/报价/追单/成交/复购 */
    @Column(name = "funnel_stage", length = 20)
    private String funnelStage;

    /** 关联场景标签 */
    @Column(name = "scene_tag", length = 50)
    private String sceneTag;

    /** 客户原话（JSONB 数组） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dialogue_quote", columnDefinition = "JSONB")
    private String dialogueQuote;

    /** 核心异议 */
    @Column(name = "objection", columnDefinition = "TEXT")
    private String objection;

    /** 情绪变化 */
    @Column(name = "emotion_change", columnDefinition = "TEXT")
    private String emotionChange;

    /** 关键动作 */
    @Column(name = "key_actions", columnDefinition = "TEXT")
    private String keyActions;

    /** 话术 */
    @Column(name = "scripts", columnDefinition = "TEXT")
    private String scripts;

    /** 推进节点 */
    @Column(name = "advance_nodes", columnDefinition = "TEXT")
    private String advanceNodes;

    /** 结果：win/loss/delay */
    @Column(name = "result", length = 10)
    private String result;

    /** 可复用点 */
    @Column(name = "reusable_points", columnDefinition = "TEXT")
    private String reusablePoints;

    /** 失败点 */
    @Column(name = "failure_points", columnDefinition = "TEXT")
    private String failurePoints;

    /** 来源出处 */
    @Column(name = "source", length = 1000)
    private String source;

    /** 来源上传任务 ID；手工录入/外部导入为空 */
    @Column(name = "source_task_id")
    private UUID sourceTaskId;

    /** 来源文档名或书名；手工录入为空 */
    @Column(name = "source_document", length = 255)
    private String sourceDocument;

    /** AI 生成依据（JSON 数组：对话/缺口/评测题引用与摘录） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "JSONB")
    private String evidence;

    /** 生成任务 ID（content_generation_job） */
    @Column(name = "generation_job_id")
    private UUID generationJobId;

    /** 人工审核人 / 时间 / 结论说明（AI 草稿审核台写入） */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    /** AI 草稿审核结论：approved / rejected / null（未审核） */
    @Column(name = "review_outcome", length = 20)
    private String reviewOutcome;

    /** 从真实对话成案时的会话 ID */
    @Column(name = "source_conversation_id")
    private UUID sourceConversationId;

    /** 贡献人 ID */
    @Column(name = "contributor_id")
    private UUID contributorId;

    /** 提交到公共库的来源企业（公共自建内容为空） */
    @Column(name = "source_company_id")
    private UUID sourceCompanyId;

    /** 置信度（AI 结构化抽取时给出，0-1） */
    @Column(name = "confidence")
    private Double confidence;

    /** 版本号 */
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    /** 被本版本取代的旧案例 ID（AI 整理 / 人工修订产生；为空表示初版） */
    @Column(name = "supersedes_id")
    private UUID supersedesId;

    /** 版本链根：同一 root 下只允许一个 active；创建时=自身 */
    @Column(name = "root_id", nullable = false)
    private UUID rootId;

    /** 生效时间 */
    @Column(name = "effective_at")
    private LocalDateTime effectiveAt;

    /** 失效时间（人工填，派生过期态） */
    @Column(name = "expire_at")
    private LocalDateTime expireAt;

    /** 状态：draft/pending/active/deprecated */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 进入 deprecated 前的状态：恢复时据此回到原状态，避免「放弃的草稿」直接变生效 */
    @Column(name = "previous_status", length = 20)
    private String previousStatus;

    /** 来源性质：internal=企业实战案例，book=书籍/课程二手案例（采信度更低） */
    @Column(name = "origin", nullable = false, length = 20)
    @Builder.Default
    private String origin = "internal";

    /** 准入阻断问题：非空时不能审批生效，需先补齐 */
    @Column(name = "quality_issues", columnDefinition = "TEXT")
    private String qualityIssues;

    /** 准入提醒项：可生效，但建议补充 */
    @Column(name = "quality_warnings", columnDefinition = "TEXT")
    private String qualityWarnings;

    /** 向量嵌入（1024 维，JPA 不管理，原生 SQL 更新） */
    @Column(name = "embedding", columnDefinition = "VECTOR(1024)", insertable = false, updatable = false)
    private String embedding;

    /** 索引状态：pending/indexing/indexed/failed */
    @Builder.Default
    @Column(name = "embedding_status", length = 20)
    private String embeddingStatus = "pending";

    /** 最近一次向量写入成功时间 */
    @Column(name = "indexed_at")
    private LocalDateTime indexedAt;

    /** 最近一次向量写入失败原因 */
    @Column(name = "index_error", columnDefinition = "TEXT")
    private String indexError;

    /** 向量化失败重试次数 */
    @Builder.Default
    @Column(name = "embedding_retry_count", nullable = false)
    private int embeddingRetryCount = 0;

    /** 下一次向量化重试时间（退避） */
    @Column(name = "embedding_next_retry_at")
    private LocalDateTime embeddingNextRetryAt;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 新条目默认自成版本链根；版本派生在 Service 显式覆盖为父链根。 */
    @jakarta.persistence.PrePersist
    void ensureRootId() {
        if (rootId == null) {
            rootId = id;
        }
    }

    // ========== 状态常量（与 KnowledgeEntry 同值） ==========

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DEPRECATED = "deprecated";

    // ========== 来源类型常量（与 KnowledgeEntry 同值） ==========

    public static final String SOURCE_TYPE_PUBLIC = "public";
    public static final String SOURCE_TYPE_PLATFORM = "platform";
    public static final String SOURCE_TYPE_DEIDENTIFIED = "deidentified";
    public static final String SOURCE_TYPE_TENANT = "tenant";

    // ========== 来源性质常量 ==========

    /** 企业自己的实战案例 */
    public static final String ORIGIN_INTERNAL = "internal";
    /** 书籍/课程里的二手案例，采信度需低于实战案例 */
    public static final String ORIGIN_BOOK = "book";

    // ========== 结果常量 ==========

    public static final String RESULT_WIN = "win";
    public static final String RESULT_LOSS = "loss";
    public static final String RESULT_DELAY = "delay";

    /** 判断结果类型是否合法（null 视为合法，即未填结果）。 */
    public static boolean isValidResult(String result) {
        return result == null || RESULT_WIN.equals(result) || RESULT_LOSS.equals(result) || RESULT_DELAY.equals(result);
    }

    /** 更新前自动设置更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
