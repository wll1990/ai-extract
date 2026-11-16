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

import java.util.Arrays;
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
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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

        var grainIds = messages.stream()
                .flatMap(m -> grainIdsOf(m).stream())
                .distinct().toList();
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
            var msgGrainIds = grainIdsOf(m);
            item.put("grainId", msgGrainIds.isEmpty() ? null : msgGrainIds.get(0).toString());
            item.put("grainIds", msgGrainIds.stream().map(UUID::toString).toList());
            item.put("reportId", m.getReportId() != null ? m.getReportId().toString() : null);
            var tags = msgGrainIds.stream().map(grainMap::get).filter(Objects::nonNull)
                    .map(ExperienceGrain::getSceneTag).filter(Objects::nonNull)
                    .distinct().toList();
            item.put("grainTags", tags.isEmpty() ? null : String.join(",", tags));
            var topGrain = msgGrainIds.isEmpty() ? null : grainMap.get(msgGrainIds.get(0));
            item.put("avgScore", topGrain != null && topGrain.getQualityScore() != null
                    ? String.valueOf(topGrain.getQualityScore()) : null);
            item.put("avgSimilarity", m.getAvgSimilarity());
            item.put("grainCount", msgGrainIds.size());
            // 知识/案例参考（新消息才有；旧消息返回空数组，前端兼容）
            com.fasterxml.jackson.databind.JsonNode refs = null;
            if (m.getSourceRefs() != null && !m.getSourceRefs().isBlank()) {
                try {
                    refs = objectMapper.readTree(m.getSourceRefs());
                } catch (Exception ignored) {
                    // 损坏的参考快照不影响消息读取
                }
            }
            item.put("knowledgeSources", refs == null ? java.util.List.of() : refs.path("knowledge"));
            item.put("caseSources", refs == null ? java.util.List.of() : refs.path("cases"));
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

    /** 历史消息的颗粒列表：优先新字段，旧数据回退单条 grainId */
    private List<UUID> grainIdsOf(com.aiextract.model.SkillMessage m) {
        if (m.getGrainIds() != null && !m.getGrainIds().isBlank()) {
            return Arrays.stream(m.getGrainIds().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .flatMap(s -> {
                        try { return java.util.stream.Stream.of(UUID.fromString(s)); }
                        catch (IllegalArgumentException e) {
                            log.warn("历史消息包含非法 grainId，已跳过: {}", s);
                            return java.util.stream.Stream.empty();
                        }
                    })
                    .distinct().toList();
        }
        return m.getGrainId() != null ? List.of(m.getGrainId()) : List.of();
    }

    /**
     * 重命名会话（属主或超管）。标题去空白后 1-200 字，空标题回退为「未命名对话」。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> renameConversation(String conversationId, String title, UUID userId) {
        assertConversationOwner(conversationId, userId);
        String clean = title == null ? "" : title.trim();
        if (clean.isEmpty()) {
            throw new BusinessException(400, "标题不能为空");
        }
        if (clean.length() > 200) {
            throw new BusinessException(400, "标题不能超过 200 字");
        }
        var conv = conversationRepository.findById(UUID.fromString(conversationId))
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.SESSION_NOT_FOUND));
        conv.setTitle(clean);
        conv.setUpdatedAt(java.time.LocalDateTime.now());
        conversationRepository.save(conv);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", conv.getId().toString());
        m.put("title", conv.getTitle());
        m.put("mode", conv.getMode());
        m.put("updatedAt", conv.getUpdatedAt() != null ? conv.getUpdatedAt().toString() : null);
        return m;
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
