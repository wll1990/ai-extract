package com.aiextract.service;

import com.aiextract.config.DomainConfig;
import com.aiextract.config.DomainConfigLoader;
import com.aiextract.dto.SkillChatRequest;
import com.aiextract.model.Skill;
import com.aiextract.repository.SkillConversationRepository;
import com.aiextract.repository.SkillMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 会话持久化服务 — 会话创建/查找、属主校验、用户/AI 消息落库。
 *
 * <p>从 {@link ChatStreamService} 提取，职责聚焦于会话和消息的 CRUD。
 * 不含任何 LLM 调用或 RAG 检索逻辑。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationPersistenceService {

    private final SkillConversationRepository conversationRepository;
    private final SkillMessageRepository skillMessageRepository;
    private final DomainConfigLoader domainConfigLoader;

    /**
     * 查找或新建会话 + 保存用户消息。
     *
     * <p>属主校验：续写时若 userId 或 skillId 不匹配，降级新建（不中断流）。</p>
     *
     * @param skillId 分身 ID
     * @param userId  用户 ID
     * @param request 聊天请求（含 conversationId、message）
     * @param mode    对话模式
     * @param now     当前时间
     * @param skill   分身对象
     * @return 会话 ID
     */
    public UUID upsertConversation(UUID skillId, UUID userId,
                                    SkillChatRequest request, String mode, LocalDateTime now, Skill skill) {
        com.aiextract.model.SkillConversation conv = null;
        if (request.getConversationId() != null && !request.getConversationId().isEmpty()) {
            UUID reqConvId = null;
            try {
                reqConvId = UUID.fromString(request.getConversationId());
            } catch (IllegalArgumentException e) {
                log.warn("非法conversationId，按新会话处理: {}", request.getConversationId());
            }
            if (reqConvId != null) {
                conv = conversationRepository.findById(reqConvId).orElse(null);
            }
            if (conv != null && (!conv.getUserId().equals(userId) || !conv.getSkillId().equals(skillId))) {
                log.warn("会话续写属主不符，降级新建 reqConvId={} owner={} requester={}",
                    conv.getId(), conv.getUserId(), userId);
                conv = null;
            }
            if (conv != null) {
                conv.setUpdatedAt(now);
                conv.setMode(mode);
                conversationRepository.save(conv);
            }
        }
        if (conv == null) {
            conv = createConversation(skillId, userId, request.getMessage(), mode, now);
        }
        log.info("Step1 会话就绪 convId={} mode={}", conv.getId(), mode);

        String roleLabel = resolveRoleLabel(skill);
        String userLabel = "practice".equals(mode) ? "我（" + roleLabel + "）" : "我";
        skillMessageRepository.save(com.aiextract.model.SkillMessage.builder()
            .id(UUID.randomUUID()).conversationId(conv.getId())
            .role("user").roleLabel(userLabel).content(request.getMessage()).createdAt(now).build());
        return conv.getId();
    }

    /**
     * 纯新建会话实体。
     */
    public com.aiextract.model.SkillConversation createConversation(
        UUID skillId, UUID userId, String firstMsg, String mode, LocalDateTime now) {
        String title = firstMsg != null && firstMsg.length() > 30
            ? firstMsg.substring(0, 30) + "..." : firstMsg;
        return conversationRepository.save(
            com.aiextract.model.SkillConversation.builder()
                .id(UUID.randomUUID()).skillId(skillId)
                .userId(userId).title(title).mode(mode)
                .createdAt(now).updatedAt(now).build());
    }

    /**
     * 保存 AI 助手回复消息，关联溯源 grainId 和 reportId。
     */
    public void saveAiMessage(UUID convId, boolean record, String content, String mode, LocalDateTime now, Skill skill,
                               UUID grainId, UUID reportId) {
        if (!record || content.isEmpty()) return;
        String counterpartyLabel = resolveCounterpartyLabel(skill);
        String aiLabel = "practice".equals(mode) ? counterpartyLabel : resolveRoleLabel(skill);
        skillMessageRepository.save(com.aiextract.model.SkillMessage.builder()
            .id(UUID.randomUUID()).conversationId(convId)
            .role("assistant").roleLabel(aiLabel)
            .content(content).grainId(grainId).reportId(reportId).createdAt(now).build());
    }

    /**
     * 从领域配置解析己方角色标签（如"销冠" / "专家"）。
     */
    public String resolveRoleLabel(Skill skill) {
        String domainId = domainConfigLoader.resolveDomain(skill);
        if (domainId == null) {
            return "专家";
        }
        DomainConfig dc = domainConfigLoader.load(domainId);
        if (dc == null || dc.getDomain() == null) return "专家";
        return dc.getDomain().getRoleLabel();
    }

    /**
     * 从领域配置解析对练中的对方角色标签（如"客户" / "对方"）。
     */
    public String resolveCounterpartyLabel(Skill skill) {
        String domainId = domainConfigLoader.resolveDomain(skill);
        if (domainId == null) {
            return "对方";
        }
        DomainConfig dc = domainConfigLoader.load(domainId);
        if (dc == null || dc.getDomain() == null) return "对方";
        return dc.getDomain().getCounterpartyLabel();
    }
}
