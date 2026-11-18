package com.aiextract.service.session;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 会话撤销补偿重放。
 *
 * <p>撤销动作依赖 Redis 删除/epoch 写入；写失败时由 {@link SessionService} 放入
 * 进程内补偿队列，这里周期重放，避免旧会话一直生效到 TTL。</p>
 */
@Component
@RequiredArgsConstructor
public class SessionRevocationRetrier {

    private final SessionService sessionService;

    @Scheduled(fixedDelayString = "${app.auth.session.retry-interval-ms:30000}",
            initialDelayString = "${app.auth.session.retry-interval-ms:30000}")
    public void retry() {
        sessionService.retryPendingRedisOps();
    }
}
