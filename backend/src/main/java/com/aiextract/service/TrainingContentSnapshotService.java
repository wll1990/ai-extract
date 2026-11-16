package com.aiextract.service;

import com.aiextract.exception.BusinessException;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.model.TrainingTask;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 培训阅读内容快照：发布时冻结知识/案例正文，避免资源后续下线/修改影响已分配批次。
 *
 * <p>陪练与考试不使用快照：陪练依赖分身实时运行，考试题面按尝试另行冻结。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingContentSnapshotService {

    private final KnowledgeEntryRepository knowledgeRepository;
    private final IndustryCaseRepository caseRepository;
    private final ObjectMapper objectMapper;

    /** 发布时调用：为所有阅读任务生成快照；跨企业资源直接阻断发布。 */
    public void captureForPublish(List<TrainingTask> tasks, UUID planCompanyId) {
        LocalDateTime now = LocalDateTime.now();
        for (TrainingTask task : tasks) {
            if (!task.isReading() || task.getResourceId() == null) {
                continue;
            }
            Map<String, Object> snapshot = build(task, planCompanyId);
            if (snapshot == null) {
                throw new BusinessException(400,
                        "任务「" + task.getTitle() + "」的资源内容无法冻结，请检查资源是否仍可用");
            }
            try {
                task.setResourceSnapshot(objectMapper.writeValueAsString(snapshot));
                task.setSnapshotVersion((task.getSnapshotVersion() == null ? 0 : task.getSnapshotVersion()) + 1);
                task.setSnapshotAt(now);
            } catch (Exception e) {
                throw new BusinessException(400,
                        "任务「" + task.getTitle() + "」的内容快照生成失败：" + e.getMessage());
            }
        }
    }

    /** 学员端读取：快照存在时直接返回，不再依赖资源当前状态。 */
    public Map<String, Object> read(TrainingTask task) {
        if (task.getResourceSnapshot() == null || task.getResourceSnapshot().isBlank()) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = objectMapper.readValue(task.getResourceSnapshot(), Map.class);
            return snapshot;
        } catch (Exception e) {
            log.warn("培训内容快照损坏 taskId={}: {}", task.getId(), e.getMessage());
            return null;
        }
    }

    /** 构建快照（发布与修复共用）；资源不存在或跨企业返回 null。 */
    public Map<String, Object> build(TrainingTask task, UUID planCompanyId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("type", task.getResourceType());
        snapshot.put("id", task.getResourceId().toString());
        snapshot.put("capturedAt", LocalDateTime.now().toString());
        if (TrainingTask.RESOURCE_KNOWLEDGE.equals(task.getResourceType())) {
            KnowledgeEntry k = knowledgeRepository.findById(task.getResourceId()).orElse(null);
            if (k == null) {
                return null;
            }
            assertSameCompany(k.getCompanyId(), planCompanyId, task.getTitle());
            snapshot.put("companyId", k.getCompanyId() == null ? null : k.getCompanyId().toString());
            snapshot.put("title", k.getTitle());
            snapshot.put("content", k.getContent());
            snapshot.put("source", k.getSource());
            snapshot.put("sourceDocument", k.getSourceDocument());
            return snapshot;
        }
        if (TrainingTask.RESOURCE_CASE.equals(task.getResourceType())) {
            IndustryCase c = caseRepository.findById(task.getResourceId()).orElse(null);
            if (c == null) {
                return null;
            }
            assertSameCompany(c.getCompanyId(), planCompanyId, task.getTitle());
            snapshot.put("companyId", c.getCompanyId() == null ? null : c.getCompanyId().toString());
            snapshot.put("title", c.getTitle());
            snapshot.put("customerProfile", c.getCustomerProfile());
            snapshot.put("funnelStage", c.getFunnelStage());
            snapshot.put("sceneTag", c.getSceneTag());
            snapshot.put("dialogueQuote", c.getDialogueQuote());
            snapshot.put("objection", c.getObjection());
            snapshot.put("emotionChange", c.getEmotionChange());
            snapshot.put("keyActions", c.getKeyActions());
            snapshot.put("scripts", c.getScripts());
            snapshot.put("advanceNodes", c.getAdvanceNodes());
            snapshot.put("result", c.getResult());
            snapshot.put("reusablePoints", c.getReusablePoints());
            snapshot.put("failurePoints", c.getFailurePoints());
            return snapshot;
        }
        return null;
    }

    /** 企业私有资源只能用于本企业培训；公共资源（companyId=null）允许。 */
    private void assertSameCompany(UUID resourceCompanyId, UUID planCompanyId, String taskTitle) {
        if (resourceCompanyId != null && !resourceCompanyId.equals(planCompanyId)) {
            throw new BusinessException(400,
                    "任务「" + taskTitle + "」引用了其他企业的资源，不能用于本企业培训");
        }
    }
}
