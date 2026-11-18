package com.aiextract.service.tool;

import com.aiextract.config.TokenContext;
import com.aiextract.model.Skill;
import com.aiextract.model.Space;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 平台只读工具 —— 供 LLM 在对话中查询当前用户的分身。
 *
 * <p>所有方法显式读取 {@link TokenContext}，拒绝跨租户数据；工具异常返回可读文本，
 * 不把异常抛回对话流。</p>
 */
@Component
@RequiredArgsConstructor
public class PlatformTools {

    private final SkillRepository skillRepository;
    private final SpaceRepository spaceRepository;

    public String listMySkills() {
        UUID userId = TokenContext.get();
        if (userId == null) {
            return "当前没有登录用户。";
        }
        List<UUID> spaceIds = spaceRepository.findByUserId(userId).stream()
                .map(Space::getId).toList();
        if (spaceIds.isEmpty()) {
            return "当前用户没有可用分身。";
        }
        List<Skill> skills = skillRepository.findBySpaceIdIn(spaceIds);
        if (skills.isEmpty()) {
            return "当前用户没有可用分身。";
        }
        return skills.stream().limit(50)
                .map(s -> s.getId() + " | " + s.getDisplayName() + " | " + s.getType() + " | " + s.getStatus())
                .collect(Collectors.joining("\n"));
    }

    public String getSkillProfile(String skillId) {
        UUID userId = TokenContext.get();
        if (userId == null) {
            return "当前没有登录用户。";
        }
        UUID id;
        try {
            id = UUID.fromString(skillId);
        } catch (Exception e) {
            return "skillId 格式不正确。";
        }
        Skill skill = skillRepository.findById(id).orElse(null);
        if (skill == null) {
            return "分身不存在。";
        }
        boolean owned = skill.getSpaceId() != null && spaceRepository.findByUserId(userId)
                .stream().anyMatch(s -> s.getId().equals(skill.getSpaceId()));
        boolean sameCompany = skill.getCompanyId() != null
                && skill.getCompanyId().equals(TokenContext.getHomeCompanyId());
        if (!owned && !sameCompany) {
            return "无权访问该分身。";
        }
        return "名称: " + skill.getDisplayName() + "\n类型: " + skill.getType() + "\n状态: " + skill.getStatus();
    }

    public String searchMySkills(String keyword) {
        UUID userId = TokenContext.get();
        if (userId == null) {
            return "当前没有登录用户。";
        }
        if (keyword == null || keyword.isBlank()) {
            return "关键词不能为空。";
        }
        List<UUID> spaceIds = spaceRepository.findByUserId(userId).stream()
                .map(Space::getId).toList();
        if (spaceIds.isEmpty()) {
            return "当前用户没有可用分身。";
        }
        String lowered = keyword.trim().toLowerCase();
        return skillRepository.findBySpaceIdIn(spaceIds).stream()
                .filter(s -> s.getDisplayName() != null && s.getDisplayName().toLowerCase().contains(lowered))
                .limit(20)
                .map(s -> s.getId() + " | " + s.getDisplayName())
                .collect(Collectors.joining("\n"));
    }
}
