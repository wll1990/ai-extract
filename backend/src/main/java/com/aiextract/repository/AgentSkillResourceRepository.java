package com.aiextract.repository;

import com.aiextract.model.AgentSkillResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentSkillResourceRepository extends JpaRepository<AgentSkillResource, UUID> {

    /** 元数据投影：只取 id/path，不加载正文。 */
    interface MetaRow {
        UUID getId();

        String getPath();

        Integer getSize();
    }

    @Query("select r.id as id, r.path as path, length(r.content) as size "
            + "from AgentSkillResource r where r.skillId = :skillId order by r.path asc")
    List<MetaRow> findMeta(@Param("skillId") UUID skillId);

    Optional<AgentSkillResource> findBySkillIdAndPath(UUID skillId, String path);

    List<AgentSkillResource> findBySkillIdOrderByPathAsc(UUID skillId);

    void deleteBySkillId(UUID skillId);
}
