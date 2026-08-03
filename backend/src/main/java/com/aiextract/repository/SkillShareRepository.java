package com.aiextract.repository;

import com.aiextract.model.SkillShare;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * @author AI Extract Team
 */
public interface SkillShareRepository extends JpaRepository<SkillShare, UUID> {
    /**
     * 查询，按，share，code。
     * @param shareCode 参数
     * @return 查询结果，可能为空
     */
    Optional<SkillShare> findByShareCode(String shareCode);
    /**
     * 查询（First,Skill,Id,Channel）。
     * @param skillId skillId
     * @param channel channel
     * @return 可能为空的查询结果
     */
    Optional<SkillShare> findFirstBySkillIdAndChannel(UUID skillId, String channel);

    /** 按渠道+开关状态查所有分享记录。 */
    List<SkillShare> findByChannelAndEnabled(String channel, boolean enabled);

}
