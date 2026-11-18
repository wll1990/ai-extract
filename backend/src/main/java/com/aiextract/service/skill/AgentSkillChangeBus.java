package com.aiextract.service.skill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.UUID;

/**
 * 技能变更广播：发布/下架后让**其他实例**立即失效目录缓存。
 *
 * <p>必须在事务提交后发布，否则其他实例可能重查旧数据并写回缓存。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSkillChangeBus {

    static final String CHANNEL = "skill:changed";
    static final String ALL = "ALL";

    private final StringRedisTemplate redisTemplate;
    private final AgentSkillRegistry registry;

    @PostConstruct
    void subscribe() {
        if (redisTemplate.getConnectionFactory() == null) {
            return;
        }
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisTemplate.getConnectionFactory());
        container.addMessageListener((MessageListener) (message, pattern) -> {
            String payload = new String(message.getBody());
            if (ALL.equals(payload)) {
                registry.invalidateAll();
            } else {
                try {
                    registry.invalidate(UUID.fromString(payload));
                } catch (IllegalArgumentException e) {
                    registry.invalidateAll();
                }
            }
            log.info("收到技能变更通知，已失效目录缓存: {}", payload);
        }, Collections.singletonList(new ChannelTopic(CHANNEL)));
        container.afterPropertiesSet();
        container.start();
    }

    /** 租户变更只清该企业；平台级变更清空所有租户缓存。 */
    public void publishAfterCommit(UUID companyId) {
        String payload = companyId == null ? ALL : companyId.toString();
        Runnable send = () -> {
            try {
                redisTemplate.convertAndSend(CHANNEL, payload);
            } catch (Exception e) {
                log.warn("技能变更广播失败（本实例已失效，其他实例靠 TTL）: {}", e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    registry.invalidate(companyId);
                    send.run();
                }
            });
        } else {
            registry.invalidate(companyId);
            send.run();
        }
    }
}
