package com.aiextract;

import com.aiextract.exception.BusinessException;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillProfile;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.SkillProfileRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.service.SkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布门槛：活跃颗粒必须完成向量化；通用状态接口不能绕过门槛；同状态幂等。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillPublishGuardTest {

    @Mock private SkillRepository skillRepository;
    @Mock private ExperienceGrainRepository grainRepository;
    @Mock private SkillProfileRepository profileRepository;

    @InjectMocks private SkillService skillService;

    private final UUID skillId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(skillService, "publishMinGrains", 10);
        ReflectionTestUtils.setField(skillService, "publishMinScenes", 3);
        when(profileRepository.findBySkillId(skillId)).thenReturn(Optional.of(completeProfile()));
    }

    @Test
    void publishBlockers_containsUnembeddedCountAndAction() {
        when(grainRepository.findBySpaceId(spaceId)).thenReturn(enoughGrains());
        when(grainRepository.countActiveWithoutEmbedding(spaceId)).thenReturn(2L);

        List<String> blockers = skillService.publishBlockers(skill("reviewing"));

        assertEquals(1, blockers.size());
        assertTrue(blockers.get(0).contains("2 条活跃颗粒尚未完成向量化"));
        assertTrue(blockers.get(0).contains("重建索引"));
    }

    @Test
    void publishBlockers_emptyWhenAllEmbedded() {
        when(grainRepository.findBySpaceId(spaceId)).thenReturn(enoughGrains());
        when(grainRepository.countActiveWithoutEmbedding(spaceId)).thenReturn(0L);

        assertTrue(skillService.publishBlockers(skill("reviewing")).isEmpty());
    }

    @Test
    void updateStatus_pausedToPublished_blockedWhenEmbeddingMissing() {
        Skill skill = skill("paused");
        when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill));
        when(grainRepository.findBySpaceId(spaceId)).thenReturn(enoughGrains());
        when(grainRepository.countActiveWithoutEmbedding(spaceId)).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> skillService.updateSkillStatus(skillId.toString(), "published"));

        assertEquals(409, e.getErrorCode());
        assertTrue(e.getMessage().contains("发布条件未满足"));
        assertEquals("paused", skill.getStatus());
        verify(skillRepository, never()).save(any(Skill.class));
    }

    @Test
    void updateStatus_generatingToPublished_blockedByInsufficientGrains() {
        Skill skill = skill("generating");
        when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill));
        when(grainRepository.findBySpaceId(spaceId)).thenReturn(List.of());
        when(grainRepository.countActiveWithoutEmbedding(spaceId)).thenReturn(0L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> skillService.updateSkillStatus(skillId.toString(), "published"));

        assertTrue(e.getMessage().contains("活跃颗粒不足"));
        assertFalse("published".equals(skill.getStatus()));
    }

    @Test
    void updateStatus_publishedToPublished_isIdempotent() {
        Skill skill = skill("published");
        when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill));

        skillService.updateSkillStatus(skillId.toString(), "published");

        verify(skillRepository, never()).save(any(Skill.class));
        verify(grainRepository, never()).countActiveWithoutEmbedding(any());
    }

    private Skill skill(String status) {
        return Skill.builder().id(skillId).spaceId(spaceId).ownerName("销冠甲")
                .status(status).type("individual").build();
    }

    private SkillProfile completeProfile() {
        return SkillProfile.builder().skillId(skillId)
                .personality("沉稳").background("十年大客户").knowledgeDomains("[\"价格\"]").build();
    }

    private List<ExperienceGrain> enoughGrains() {
        List<ExperienceGrain> grains = new ArrayList<>();
        String[] tags = {"价格异议", "预算冻结", "竞品对比"};
        for (int i = 0; i < 12; i++) {
            grains.add(ExperienceGrain.builder()
                    .id(UUID.randomUUID()).spaceId(spaceId).status("active")
                    .sceneTag(tags[i % tags.length]).build());
        }
        return grains;
    }
}
