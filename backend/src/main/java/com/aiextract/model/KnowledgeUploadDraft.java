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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文档萃取草稿 — KN-06 上传结果先预览、再批量采纳/拒绝。
 *
 * <p>萃取阶段只落草稿（pending），管理端确认后由 adopt 接口创建正式知识条目；
 * 已采纳/已拒绝的草稿保留用于「查看历史结果」，不再重复采纳。</p>
 */
@Entity
@Table(name = "knowledge_upload_draft")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeUploadDraft {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ADOPTED = "adopted";
    public static final String STATUS_REJECTED = "rejected";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    /** 来源章节序号（从 1 开始），用于按章节展示/重试 */
    @Column(name = "section_no", nullable = false)
    private Integer sectionNo;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "category", nullable = false, length = 50)
    private String category;

    @Column(name = "source", length = 500)
    private String source;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 质量分 0-100（准入校验时计算，页面排序/提示用） */
    @Column(name = "quality_score")
    private Integer qualityScore;

    /** 阻断性问题：非空时不允许直接采纳，必须先补齐 */
    @Column(name = "quality_issues", columnDefinition = "TEXT")
    private String qualityIssues;

    /** 提醒项：可采纳，但建议补充 */
    @Column(name = "quality_warnings", columnDefinition = "TEXT")
    private String qualityWarnings;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
