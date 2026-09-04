package com.aiextract.repository;

import com.aiextract.model.ImChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
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

    /**
     * 按平台 bot 标识查询渠道（O(1) 路由）
     *
     * @param botId 平台 bot 标识（飞书 app_id 等）
     * @return 渠道
     */
    Optional<ImChannel> findByBotId(String botId);

    /**
     * 按渠道类型 + 状态查询（开机对账拉取需连接的 bot）
     *
     * @param channelType 渠道类型
     * @param status      生命周期状态
     * @return 渠道列表
     */
    List<ImChannel> findByChannelTypeAndStatus(String channelType, String status);

    /**
     * 判断企业+渠道+分身是否已绑定（防重复绑定）
     *
     * @param companyId   企业ID
     * @param channelType 渠道类型
     * @param skillId     分身ID
     * @return 是否存在绑定
     */
    boolean existsByCompanyIdAndChannelTypeAndSkillId(UUID companyId, String channelType, UUID skillId);

    /**
     * 查询所有已绑定 bot 的渠道（bot_id 非空），super_admin 全局列表用，避免 findAll + 内存过滤
     */
    List<ImChannel> findByBotIdIsNotNull();
}
