package com.aiextract.service;

import com.aiextract.model.User;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.session.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 用户安全事件服务 —— 权限/状态变更后让旧会话立即失效的唯一入口。
 *
 * <p>会话撤销已收敛到 {@link SessionService}：DB 内 bump token_version 并在同一事务内
 * 标记会话 revoked，事务提交后刷新 Redis epoch。所有旧的 “安全版本号 +1” 调用点
 * 自动获得“踢掉全部会话”的语义。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSecurityService {

    private final UserRepository userRepository;
    private final SessionService sessionService;

    /**
     * 权限/状态变更（调用方手里已有托管实体）。
     *
     * <p>必须用实体自增而不是 JPQL 自增：调用方紧接着可能用同一个 User 对象签发新会话，
     * JPQL 自增不会刷新内存里的值，会签出「刚生成就失效」的会话。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void onAuthoritiesChanged(User user) {
        if (user == null || user.getId() == null) {
            return;
        }
        long next = (user.getTokenVersion() == null ? 0L : user.getTokenVersion()) + 1;
        user.setTokenVersion(next);
        // 必须先落库：revokeAllWithEpoch 内部的批量 UPDATE 会 clear 持久化上下文，
        // 未 flush 的版本号会被丢弃，导致后续 revokeAll 算出的 epoch 与会话快照相同，
        // 已撤销的会话在 Redis 命中路径继续有效。
        userRepository.saveAndFlush(user);
        sessionService.revokeAllWithEpoch(user.getId(), next);
        log.info("用户权限变更，安全版本号 +1：userId={}, ver={}", user.getId(), next);
    }

    /**
     * 权限/状态变更（只有 userId，批量或跨服务场景）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void onAuthoritiesChanged(UUID userId) {
        if (userId == null) {
            return;
        }
        int updated = userRepository.bumpTokenVersion(userId);
        if (updated == 0) {
            log.warn("安全版本号自增失败：用户不存在 userId={}", userId);
            return;
        }
        Long newVersion = userRepository.findTokenVersionById(userId);
        if (newVersion == null) {
            return;
        }
        sessionService.revokeAllWithEpoch(userId, newVersion);
        log.info("用户权限变更，安全版本号 +1：userId={}, ver={}", userId, newVersion);
    }
}
