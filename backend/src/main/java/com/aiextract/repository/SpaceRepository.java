package com.aiextract.repository;

import com.aiextract.model.Space;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

/**
 * @author AI Extract Team
 */
@Repository
public interface SpaceRepository extends JpaRepository<Space, UUID> {
    /**
     * 查询，按，user，id。
     * @param userId 参数
     * @return 查询结果列表
     */
    List<Space> findByUserId(UUID userId);
    /**
     * 查询（User,Id）。
     * @param userIds userIds
     * @return 结果列表
     */
    List<Space> findByUserIdIn(List<UUID> userIds);

    Page<Space> findByUserIdIn(List<UUID> userIds, Pageable pageable);

    /** 按标题模糊搜索 + 用户过滤 */
    Page<Space> findByTitleContainingIgnoreCaseAndUserIdIn(String keyword, List<UUID> userIds, Pageable pageable);

    /** 按标题模糊搜索 */
    Page<Space> findByTitleContainingIgnoreCase(String keyword, Pageable pageable);
}

