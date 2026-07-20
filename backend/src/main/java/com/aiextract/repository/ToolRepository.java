package com.aiextract.repository;

import com.aiextract.model.Tool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

/**
 * 工具数据访问接口
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Repository
public interface ToolRepository extends JpaRepository<Tool, UUID> {
    /**
     * 查询By Space Id。
     * @param spaceId 参数
     * @return 结果列表
     */
    List<Tool> findBySpaceId(UUID spaceId);
    /**
     * 查询（Type）。
     * @param type type
     * @return 结果列表
     */
    List<Tool> findByType(String type);


}
