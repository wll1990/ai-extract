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
 * 知识/案例召回日志 — 分身回答命中知识或案例时写一行。
 *
 * <p>只用于「被引用」影响面展示与废弃前提示，不参与检索；
 * 由 DataCleanupScheduler 按 30 天清理。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-13
 */
@Entity
@Table(name = "knowledge_case_retrieve_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeCaseRetrieveLog {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 命中的知识 ID（与 caseId 二选一） */
    @Column(name = "knowledge_id")
    private UUID knowledgeId;

    /** 命中的案例 ID（与 knowledgeId 二选一） */
    @Column(name = "case_id")
    private UUID caseId;

    /** 产生引用的分身 ID；组织分身统一调度时为空 */
    @Column(name = "skill_id")
    private UUID skillId;

    /** 归属企业（同企业聊天为分身企业；公共检索为 null） */
    @Column(name = "company_id")
    private UUID companyId;

    /** 使用场景：skill_chat / org_chat / enterprise_chat / evaluation / training_reading / public_chat */
    @Builder.Default
    @Column(name = "usage_type", nullable = false, length = 32)
    private String usageType = "skill_chat";

    /** 使用上下文 ID：会话/尝试/任务记录 */
    @Column(name = "usage_ref_id")
    private UUID usageRefId;

    /** 产生引用的会话 ID */
    @Column(name = "conversation_id")
    private UUID conversationId;

    /** 相似度（0-1） */
    @Column(name = "similarity")
    private Double similarity;

    /** 幂等键：评测按 runItem+条目、培训阅读按 assignment+task+user+day 去重 */
    @Column(name = "dedupe_key", length = 200)
    private String dedupeKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
