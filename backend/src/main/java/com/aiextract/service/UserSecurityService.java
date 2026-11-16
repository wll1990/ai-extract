package com.aiextract.service;

import com.aiextract.config.TokenRevocationService;
import com.aiextract.model.User;
import com.aiextract.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * 用户安全事件服务 —— 权限/状态变更后让旧 token 立即失效的唯一入口。
 *
 * <p><b>为什么需要它</b>：JWT 自包含 permissions，签发后 24 小时（C 端 30 天）内不查 DB。
 * 改角色、停用账号、封号之后，必须主动把「安全版本号」加一，否则旧 token 继续有效。</p>
 *
 * <p><b>两个必须遵守的约定</b>：</p>
 * <ol>
 *   <li>DB 的 {@code token_version + 1} 必须在**业务事务内**执行——业务回滚时版本一起回滚；</li>
 *   <li>Redis 写入必须在**事务提交后**执行——否则回滚后 Redis 残留高版本，会把用户的正常 token 错杀。</li>
 * </ol>
 *
 * <p>Redis 写失败不影响主流程：鉴权侧在缓存未命中时会回源 DB，正确性不依赖 Redis。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSecurityService {

    private final UserRepository userRepository;
    private final TokenRevocationService revocationService;

    /**
     * 权限/状态变更（调用方手里已有托管实体）。
     *
     * <p>必须用实体自增而不是 JPQL 自增：调用方紧接着可能用同一个 User 对象签发新 token，
     * JPQL 自增不会刷新内存里的值，会签出「刚生成就失效」的 token。
     * 调用前提：{@code user} 是当前事务的托管实体，且变更已 flush（或将被 flush）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void onAuthoritiesChanged(User user) {
        if (user == null || user.getId() == null) {
            return;
        }
        long next = (user.getTokenVersion() == null ? 0L : user.getTokenVersion()) + 1;
        user.setTokenVersion(next);
        runAfterCommit(() -> revocationService.cacheVersion(user.getId(), next));
        log.info("用户权限变更，安全版本号 +1：userId={}, ver={}", user.getId(), next);
    }

    /**
     * 权限/状态变更（只有 userId，批量或跨服务场景）。
     *
     * <p>同一事务内先 UPDATE 再 SELECT，拿到自增后的新值写缓存。</p>
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
        runAfterCommit(() -> revocationService.cacheVersion(userId, newVersion));
        log.info("用户权限变更，安全版本号 +1：userId={}, ver={}", userId, newVersion);
    }

    /**
     * 事务提交后执行；无事务时立即执行。
     *
     * <p>失败只记日志：Redis 写失败只影响下一次鉴权的性能，正确性由 DB 回源保证。</p>
     */
    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runSafely(action);
                }
            });
        } else {
            runSafely(action);
        }
    }

    private void runSafely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("安全版本号缓存写入失败（不影响正确性，鉴权会回源 DB）", e);
        }
    }
}
