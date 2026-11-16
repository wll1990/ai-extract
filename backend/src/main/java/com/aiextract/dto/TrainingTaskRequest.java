package com.aiextract.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * 培训任务创建/更新请求 DTO。
 *
 * <p>三种类型的必填项由 Service 与数据库 CHECK 双重保证：
 * reading 必须带 knowledge/case 资源，practice 必须绑分身（resourceType=skill），
 * exam 必须带已发布题集版本与考试时长，且 {@code allowHints} 强制 false。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingTaskRequest {

    /** 类型：reading/practice/exam/drill */
    @NotBlank(message = "任务类型不能为空")
    private String type;

    /**
     * 场景微练内容（仅 drill）：AI 生成后由前端原样带回。
     * 结构：{"scenario":"...","questions":[{"question":"...","context":"...","expectedPoints":[...],"scoringRule":"..."}]}
     */
    private String drillScenario;

    /** 阶段序号（默认 1） */
    @Min(value = 1, message = "阶段序号从 1 开始")
    private Integer phaseNo;

    /** 阶段名（如「第一周：产品知识」） */
    @Size(max = 100, message = "阶段名不能超过 100 字")
    private String phaseName;

    /** 任务标题 */
    @NotBlank(message = "任务标题不能为空")
    @Size(max = 255, message = "任务标题不能超过 255 字")
    private String title;

    /** 任务说明 */
    private String description;

    /** 是否必修（默认 true） */
    private Boolean isRequired;

    /** 阶段内顺序（省略=该阶段末尾） */
    private Integer orderNo;

    /** 前置任务 ID 列表（必须属于同一版本） */
    private List<UUID> prerequisites;

    /** 资源类型：knowledge/case/skill */
    private String resourceType;

    /** 资源 ID（practice 时=分身 skill_id） */
    private UUID resourceId;

    /** 陪练场景标签 */
    @Size(max = 50, message = "场景标签不能超过 50 字")
    private String sceneTag;

    /** 是否允许提示（考试任务服务端强制 false） */
    private Boolean allowHints;

    /** 考试题集 ID */
    private UUID examDatasetId;

    /** 考试题集版本 */
    private Integer examDatasetVersion;

    /** 抽题数（省略=全量） */
    private Integer examItemCount;

    /** 考试时长（分钟，exam 必填） */
    private Integer examDurationMinutes;

    /** 通过条件 JSON，如 {"minScore":80,"minPointCoverage":0.7} */
    private String passCriteria;

    /** 总尝试次数（含首次，默认 1） */
    @Min(value = 1, message = "尝试次数至少为 1")
    private Integer maxAttempts;

    /** 逾期策略：auto_submit/timeout_fail */
    private String deadlinePolicy;
}
