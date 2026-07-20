package com.aiextract.repository;

import com.aiextract.model.ImChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * IM渠道数据访问接口
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Repository
public interface ImChannelRepository extends JpaRepository<ImChannel, UUID> {

    /**
     * 按企业ID查询渠道列表
     *
     * @param companyId 企业ID
     * @return 渠道列表
     */
    List<ImChannel> findByCompanyId(UUID companyId);

    /**
     * 按渠道类型和企业ID查询
     *
     * @param companyId   企业ID
     * @param channelType 渠道类型
     * @return 渠道列表
     */
    List<ImChannel> findByCompanyIdAndChannelType(UUID companyId, String channelType);

    /**
     * 按渠道类型查询所有渠道（多企业场景用 appId 匹配）
     *
     * @param channelType 渠道类型
     * @return 渠道列表
     */
    List<ImChannel> findByChannelType(String channelType);
}
