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
 * 企业知识库条目实体 — 事实型知识（产品/竞品/价格/合规/术语/方法论）。
 *
 * <p>与经验颗粒 {@link ExperienceGrain} 严格区分：经验是「场景+话术+思路」，
 * 知识是「事实」，验证靠来源核对而非对抗验证。company_id 为 NULL 表示平台公共知识
 * （所有租户可见、模板化授权），非 NULL 表示租户私有知识。</p>
 *
 * <p>状态只存 draft/pending/active/deprecated；「过期」由 expire_at 派生，
 * 不落库、不做定时器。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Entity
@Table(name = "knowledge_entry")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeEntry {

    /** 知识条目唯一标识 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属企业 ID，NULL=平台公共知识 */
    @Column(name = "company_id")
    private UUID companyId;

    /** 来源类型：public/platform/deidentified/tenant */
    @Column(name = "source_type", length = 20, nullable = false)
    private String sourceType;

    /** 内容类型：compliance/price/product/policy/competitor/terminology/methodology/industry_intro */
    @Column(name = "category", length = 20, nullable = false)
    private String category;

    /** 知识标题 */
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** 知识正文（事实内容） */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 版本号（每次 AI 整理+人工确认后 +1） */
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    /** 被本版本取代的旧版本；审批通过时才把旧版置 deprecated */
    @Column(name = "supersedes_id")
    private UUID supersedesId;

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

    /** 来源出处（版权标注，公共知识必填） */
    @Column(name = "source", length = 1000)
    private String source;

    /** 来源上传任务 ID；手工录入/外部导入为空 */
    @Column(name = "source_task_id")
    private UUID sourceTaskId;

    /** 来源文档名或书名；手工录入为空 */
    @Column(name = "source_document", length = 255)
    private String sourceDocument;

    /** AI 生成依据（JSON 数组：缺口/对话/评测题的引用与摘录），人工录入为空 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "JSONB")
    private String evidence;

    /** 生成任务 ID（content_generation_job），用于采纳率与追溯 */
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

    /** 版本链根：同一 root 下只允许一个 active；创建时=自身 */
    @Column(name = "root_id", nullable = false)
    private UUID rootId;

    /** 来源类型：internal / book / external */
    @Builder.Default
    @Column(name = "origin", nullable = false, length = 20)
    private String origin = ORIGIN_INTERNAL;

    /** 书籍模式：编辑/审批时沿用 600 字与出处要求 */
    @Builder.Default
    @Column(name = "book_mode", nullable = false)
    private boolean bookMode = false;

    /** 创建人 ID */
    @Column(name = "created_by")
    private UUID createdBy;

    /** 提交到公共库的来源企业（公共自建内容为空） */
    @Column(name = "source_company_id")
    private UUID sourceCompanyId;

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

    // ========== 状态常量 ==========

    public static final String ORIGIN_INTERNAL = "internal";
    public static final String ORIGIN_BOOK = "book";
    public static final String ORIGIN_EXTERNAL = "external";

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DEPRECATED = "deprecated";

    // ========== 来源类型常量 ==========

    public static final String SOURCE_TYPE_PUBLIC = "public";
    public static final String SOURCE_TYPE_PLATFORM = "platform";
    public static final String SOURCE_TYPE_DEIDENTIFIED = "deidentified";
    public static final String SOURCE_TYPE_TENANT = "tenant";

    // ========== 内容类型常量 ==========

    public static final String CATEGORY_COMPLIANCE = "compliance";
    public static final String CATEGORY_PRICE = "price";
    public static final String CATEGORY_PRODUCT = "product";
    public static final String CATEGORY_POLICY = "policy";
    public static final String CATEGORY_COMPETITOR = "competitor";
    public static final String CATEGORY_TERMINOLOGY = "terminology";
    public static final String CATEGORY_METHODOLOGY = "methodology";
    public static final String CATEGORY_INDUSTRY_INTRO = "industry_intro";

    // ========== 校验辅助 ==========

    private static final java.util.Set<String> CATEGORIES = java.util.Set.of(
            CATEGORY_COMPLIANCE, CATEGORY_PRICE, CATEGORY_PRODUCT, CATEGORY_POLICY,
            CATEGORY_COMPETITOR, CATEGORY_TERMINOLOGY, CATEGORY_METHODOLOGY, CATEGORY_INDUSTRY_INTRO);

    private static final java.util.Set<String> SOURCE_TYPES = java.util.Set.of(
            SOURCE_TYPE_PUBLIC, SOURCE_TYPE_PLATFORM, SOURCE_TYPE_DEIDENTIFIED, SOURCE_TYPE_TENANT);

    /** 判断内容分类是否合法。 */
    public static boolean isValidCategory(String category) {
        return category != null && CATEGORIES.contains(category);
    }

    /** 判断来源类型是否合法。 */
    public static boolean isValidSourceType(String sourceType) {
        return sourceType != null && SOURCE_TYPES.contains(sourceType);
    }

    /** 更新前自动设置更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
