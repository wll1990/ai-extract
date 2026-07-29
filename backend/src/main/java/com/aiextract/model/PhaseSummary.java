package com.aiextract.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 访谈阶段摘要 — 长访谈 token 优化。
 * 阶段完成时异步生成，后续阶段注入 system prompt 替代全量历史消息。
 *
 * @author AI Extract Team
 * @since 2026-07-30
 */
@Entity
@Table(name = "interview_phase_summary")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhaseSummary {

    /** 主键 */
    @Id
    private UUID id;

    /** 关联的访谈会话 ID */
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    /** 阶段标识: opening / storytelling / modeling / closing */
    @Column(name = "phase", nullable = false, length = 20)
    private String phase;

    /** 阶段中文标签: 开场定调 / 故事深描 / 模型提炼 / 收网确认 */
    @Column(name = "phase_label", nullable = false, length = 20)
    private String phaseLabel;

    /** AI 生成的本阶段已收集关键信息摘要（3-5 句话） */
    @Column(name = "summary", nullable = false, columnDefinition = "TEXT")
    private String summary;

    /** 摘要生成时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
