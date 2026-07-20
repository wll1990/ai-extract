package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.model.SkillConversation;
import com.aiextract.model.SkillMessage;
import com.aiextract.repository.SkillConversationRepository;
import com.aiextract.repository.SkillMessageRepository;
import com.aiextract.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
/**
 * @author AI Extract Team
 */
@RequiredArgsConstructor
public class AdminConversationController {

    private final SkillConversationRepository conversationRepository;
    private final SkillMessageRepository messageRepository;
    private final com.aiextract.repository.SkillRepository skillRepository;
    private final com.aiextract.repository.UserRepository userRepository;

    /** 对话历史列表（管理员） */
    @GetMapping("/admin/conversations")
    public ApiResponse<Map<String, Object>> listConversations(
            @RequestParam(required = false) UUID skillId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<SkillConversation> convPage;
        if (skillId != null) {
            convPage = conversationRepository.findBySkillIdOrderByUpdatedAtDesc(skillId, PageRequest.of(page - 1, size));
        } else {
            convPage = conversationRepository.findAllByOrderByUpdatedAtDesc(PageRequest.of(page - 1, size));
        }

        // 批量查 skill 和 user，避免 N+1
        List<UUID> skillIds = convPage.getContent().stream().map(SkillConversation::getSkillId).distinct().toList();
        List<UUID> userIds = convPage.getContent().stream().map(SkillConversation::getUserId).distinct().toList();
        Map<UUID, String> skillNames = skillRepository.findAllById(skillIds).stream()
                .collect(Collectors.toMap(
                        com.aiextract.model.Skill::getId,
                        s -> s.getOwnerName() != null ? s.getOwnerName()
                                : s.getDisplayName() != null ? s.getDisplayName() : "未命名",
                        (a, b) -> a));
        Map<UUID, String> userNames = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(
                        com.aiextract.model.User::getId,
                        com.aiextract.model.User::getName,
                        (a, b) -> a));

        // 批量查消息数，避免 N+1
        List<UUID> convIds = convPage.getContent().stream().map(SkillConversation::getId).toList();
        Map<UUID, Long> msgCounts = messageRepository.countByConversationIdIn(convIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1], (a, b) -> a));

        List<Map<String, Object>> items = convPage.getContent().stream().map(conv -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", conv.getId().toString());
            item.put("skillId", conv.getSkillId().toString());
            item.put("skillName", skillNames.getOrDefault(conv.getSkillId(), "未知"));
            item.put("userName", userNames.getOrDefault(conv.getUserId(), "未知用户"));
            item.put("mode", conv.getMode());
            item.put("title", conv.getTitle());
            item.put("messageCount", msgCounts.getOrDefault(conv.getId(), 0L).intValue());
            item.put("updatedAt", conv.getUpdatedAt().toString());
            return item;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", items);
        result.put("total", convPage.getTotalElements());
        result.put("totalPages", convPage.getTotalPages());
        return ApiResponse.success(result);
    }

    /** 对话回放 — 获取指定会话的全部消息 */
    @GetMapping("/admin/conversations/{conversationId}/messages")
    public ApiResponse<List<SkillMessage>> getConversationMessages(@PathVariable UUID conversationId) {
        return ApiResponse.success(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId));
    }
}
