package com.aiextract.repository;

import com.aiextract.model.ExpertDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * @author AI Extract Team
 */
@Repository
public interface ExpertDocumentRepository extends JpaRepository<ExpertDocument, UUID> {
    /**
     * 查询，按，expert，id。
     * @param expertId 参数
     * @return 查询结果列表
     */
    List<ExpertDocument> findByExpertId(UUID expertId);

    @Query("SELECT ed.expertId, COUNT(ed) FROM ExpertDocument ed WHERE ed.expertId IN :ids GROUP BY ed.expertId")
    /**
     * 统计（Expert,Id）。
     * @param ids ids
     * @return 结果列表
     */
    List<Object[]> countByExpertIdIn(@Param("ids") List<UUID> ids);

    /** 删除萃取师下所有文档 */
    void deleteByExpertId(UUID expertId);

}
