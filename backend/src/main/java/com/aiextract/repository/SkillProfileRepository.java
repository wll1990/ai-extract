package com.aiextract.repository;

import com.aiextract.model.SkillProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * @author AI Extract Team
 */
public interface SkillProfileRepository extends JpaRepository<SkillProfile, UUID> {
    /**
     * 查询，按，skill，id。
     * @param skillId 参数
     * @return 查询结果，可能为空
     */
    Optional<SkillProfile> findBySkillId(UUID skillId);
}
