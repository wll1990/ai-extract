package com.aiextract.service;

import com.aiextract.common.ErrorMessages;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Report;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.ReportRepository;
import com.aiextract.repository.SkillConversationRepository;
import com.aiextract.repository.SkillMessageRepository;
import com.aiextract.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 对话会话管理服务 — 会话列表、消息查询、删除、属主校验。
 *
 * <p>从 {@link SkillService} 提取，职责聚焦于会话生命周期管理。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final SkillConversationRepository conversationRepository;
    private final SkillMessageRepository skillMessageRepository;
    private final UserRepository userRepository;
    private final ExperienceGrainRepository grainRepository;
    private final ReportRepository reportRepository;

    /**
     * 校验会话属主。非属主且非超级管理员时抛出 403。
     */
    public void assertConversationOwner(String conversationId, UUID userId) {
        var conv = conversationRepository.findById(UUID.fromString(conversationId))
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.SESSION_NOT_FOUND));
        if (conv.getUserId().equals(userId)) {
            return;
        }
        boolean isSuperAdmin = userRepository.findById(userId)
                .map(u -> com.aiextract.config.RolePermissions.hasPermission(
                    u.getRole(), com.aiextract.config.Permission.CONVERSATION_VIEW)).orElse(false);
        if (!isSuperAdmin) {
            log.warn("会话越权访问被拦截 convId={} owner={} requester={}", conversationId, conv.getUserId(), userId);
            throw new BusinessException(403, "无权访问该会话");
        }
    }

    /**
     * 查询用户在指定分身下的会话列表，按更新时间降序。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listConversations(String skillId, UUID userId) {
        return conversationRepository.findBySkillIdAndUserIdOrderByUpdatedAtDesc(UUID.fromString(skillId), userId)
                .stream().map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId().toString());
                    m.put("title", c.getTitle());
                    m.put("mode", c.getMode());
                    m.put("updatedAt", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null);
                    return m;
                }).collect(Collectors.toList());
    }

    /**
     * 获取会话全部消息（含溯源字段），属主校验通过后方可查看。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getConversationMessages(String conversationId, UUID userId) {
        assertConversationOwner(conversationId, userId);
        var messages = skillMessageRepository.findByConversationIdOrderByCreatedAtAsc(UUID.fromString(conversationId));

        var grainIds = messages.stream().map(m -> m.getGrainId()).filter(Objects::nonNull).distinct().toList();
        var reportIds = messages.stream().map(m -> m.getReportId()).filter(Objects::nonNull).distinct().toList();

        var grainMap = grainIds.isEmpty() ? Collections.<UUID, ExperienceGrain>emptyMap()
                : grainRepository.findAllById(grainIds).stream().collect(Collectors.toMap(g -> g.getId(), g -> g));
        var reportMap = reportIds.isEmpty() ? Collections.<UUID, Report>emptyMap()
                : reportRepository.findAllById(reportIds).stream().collect(Collectors.toMap(r -> r.getId(), r -> r));

        return messages.stream().map(m -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", m.getId().toString());
            item.put("role", m.getRole());
            item.put("content", m.getContent());
            item.put("createdAt", m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
            item.put("grainId", m.getGrainId() != null ? m.getGrainId().toString() : null);
            item.put("reportId", m.getReportId() != null ? m.getReportId().toString() : null);
            if (m.getGrainId() != null) {
                var grain = grainMap.get(m.getGrainId());
                item.put("grainTags", grain != null ? grain.getSceneTag() : null);
                item.put("avgScore", grain != null && grain.getQualityScore() != null
                        ? String.valueOf(grain.getQualityScore()) : null);
            } else {
                item.put("grainTags", null);
                item.put("avgScore", null);
            }
            item.put("grainCount", m.getGrainId() != null ? 1 : 0);
            if (m.getReportId() != null) {
                var report = reportMap.get(m.getReportId());
                item.put("reportTitle", report != null ? report.getTitle() : null);
                item.put("source", report != null ? report.getTitle() : null);
            } else {
                item.put("reportTitle", null);
                item.put("source", null);
            }
            return item;
        }).collect(Collectors.toList());
    }

    /**
     * 删除会话及关联消息。属主校验通过后方可执行。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(String conversationId, UUID userId) {
        assertConversationOwner(conversationId, userId);
        skillMessageRepository.deleteByConversationId(UUID.fromString(conversationId));
        conversationRepository.deleteById(UUID.fromString(conversationId));
    }
}
