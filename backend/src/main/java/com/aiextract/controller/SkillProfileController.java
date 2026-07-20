package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.model.SkillProfile;
import com.aiextract.repository.SkillProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
/**
 * @author AI Extract Team
 */
@RequiredArgsConstructor
public class SkillProfileController {
    private static final String KEY_BACKGROUND = "background";
    private static final String KEY_COMMON_PHRASES = "commonPhrases";
    private static final String KEY_COMMUNICATION_PREFERENCES = "communicationPreferences";
    private static final String KEY_EXTRA_CONTEXT = "extraContext";
    private static final String KEY_KNOWLEDGE_DOMAINS = "knowledgeDomains";
    private static final String KEY_PERSONALITY = "personality";
    private static final String KEY_SPEAKING_STYLE = "speakingStyle";
    private static final String KEY_WEAKNESS_NOTES = "weaknessNotes";


    private final SkillProfileRepository profileRepository;

    /** 获取分身画像 */
    @GetMapping("/admin/skills/{skillId}/profile")
    public ApiResponse<SkillProfile> getProfile(@PathVariable UUID skillId) {
        return ApiResponse.success(
            profileRepository.findBySkillId(skillId).orElse(null));
    }

    /** 创建或更新分身画像 */
    @PutMapping("/admin/skills/{skillId}/profile")
    public ApiResponse<SkillProfile> saveProfile(
            @PathVariable UUID skillId,
            @RequestBody Map<String, Object> body) {
        SkillProfile profile = profileRepository.findBySkillId(skillId)
            .orElse(SkillProfile.builder().skillId(skillId).build());

        if (body.containsKey(KEY_PERSONALITY)) { profile.setPersonality((String) body.get(KEY_PERSONALITY)); }
        if (body.containsKey(KEY_SPEAKING_STYLE)) { profile.setSpeakingStyle((String) body.get(KEY_SPEAKING_STYLE)); }
        if (body.containsKey(KEY_BACKGROUND)) { profile.setBackground((String) body.get(KEY_BACKGROUND)); }
        if (body.containsKey(KEY_COMMON_PHRASES)) { profile.setCommonPhrases((String) body.get(KEY_COMMON_PHRASES)); }
        if (body.containsKey(KEY_KNOWLEDGE_DOMAINS)) { profile.setKnowledgeDomains((String) body.get(KEY_KNOWLEDGE_DOMAINS)); }
        if (body.containsKey(KEY_COMMUNICATION_PREFERENCES)) { profile.setCommunicationPreferences((String) body.get(KEY_COMMUNICATION_PREFERENCES)); }
        if (body.containsKey(KEY_WEAKNESS_NOTES)) { profile.setWeaknessNotes((String) body.get(KEY_WEAKNESS_NOTES)); }
        if (body.containsKey(KEY_EXTRA_CONTEXT)) { profile.setExtraContext((String) body.get(KEY_EXTRA_CONTEXT)); }

        return ApiResponse.success(profileRepository.save(profile));
    }
}
