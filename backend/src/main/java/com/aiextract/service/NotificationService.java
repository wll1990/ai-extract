package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.UserNotification;
import com.aiextract.repository.UserNotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 站内信通用投递与收件箱。
 *
 * <p>业务事务提交后独立短事务投递；通知失败只记日志，不影响主流程。</p>
 */
@Slf4j
@Service
public class NotificationService {

    private final UserNotificationRepository repository;
    private final ImPushService imPushService;
    private final TransactionTemplate requiresNew;

    public NotificationService(UserNotificationRepository repository,
                               ImPushService imPushService,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.imPushService = imPushService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 事务提交后投递；无事务时立即投递。失败只记日志（业务通知可丢）。 */
    public void notifyUser(UUID userId, UUID companyId, String type, String title,
                           String content, String link, String bizType, UUID bizId,
                           String dedupeKey) {
        if (userId == null) {
            log.warn("站内信缺少收件人 userId={} type={}", userId, type);
            return;
        }
        Runnable task = () -> write(userId, companyId, type, title, content, link,
                bizType, bizId, dedupeKey);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    /**
     * 立即投递（独立事务），返回是否成功。
     *
     * <p>提醒类调度用它：只有真正写入成功，调用方才置「已提醒」标志，
     * 否则标志会先提交、通知已丢失且无法重试（P1-9）。</p>
     */
    public boolean notifyNow(UUID userId, UUID companyId, String type, String title,
                             String content, String link, String bizType, UUID bizId,
                             String dedupeKey) {
        if (userId == null) {
            log.warn("站内信缺少收件人 userId={} type={}", userId, type);
            return false;
        }
        return write(userId, companyId, type, title, content, link, bizType, bizId, dedupeKey);
    }

    public Page<UserNotification> listMine(boolean unreadOnly, int page, int size) {
        UUID userId = TokenContext.get();
        int safeSize = Math.min(Math.max(size, 1), 100);
        PageRequest pageable = PageRequest.of(Math.max(page, 1) - 1, safeSize);
        return unreadOnly
                ? repository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId, pageable)
                : repository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    public long unreadCount() {
        return repository.countByUserIdAndReadAtIsNull(TokenContext.get());
    }

    /** @Modifying 查询不自带事务，必须显式开启，否则 500 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void markRead(UUID id) {
        UUID userId = TokenContext.get();
        if (repository.findByIdAndUserId(id, userId).isEmpty()) {
            throw new BusinessException(404, "通知不存在");
        }
        repository.markRead(id, userId, LocalDateTime.now());
    }

    /** @Modifying 查询不自带事务，必须显式开启，否则 500 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public int markAllRead() {
        return repository.markAllRead(TokenContext.get(), LocalDateTime.now());
    }

    /** NT-04 删除本人通知；别人的通知一律按不存在处理 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void deleteMine(UUID id) {
        UUID userId = TokenContext.get();
        UserNotification n = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(404, "通知不存在"));
        repository.delete(n);
    }

    private boolean write(UUID userId, UUID companyId, String type, String title, String content,
                          String link, String bizType, UUID bizId, String dedupeKey) {
        try {
            Boolean ok = requiresNew.execute(status -> {
                repository.save(UserNotification.builder()
                        .id(UUID.randomUUID())
                        .companyId(companyId)
                        .userId(userId)
                        .type(type)
                        .title(title)
                        .content(content)
                        .link(link)
                        .bizType(bizType)
                        .bizId(bizId)
                        .dedupeKey(dedupeKey)
                        .createdAt(LocalDateTime.now())
                        .build());
                return Boolean.TRUE;
            });
            boolean saved = Boolean.TRUE.equals(ok);
            // 站内信落库成功后再外发 IM；未绑定/渠道停用由 ImPushService 自行跳过
            if (saved) {
                imPushService.pushAsync(userId, companyId, type, title, content, link);
            }
            return saved;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // dedupe_key 唯一冲突 = 这条通知已经投递过，按成功处理，避免调度反复重试
            log.debug("站内信已存在，跳过 userId={} type={} dedupe={}", userId, type, dedupeKey);
            return true;
        } catch (Exception e) {
            log.warn("站内信投递失败 userId={} type={} dedupe={}", userId, type, dedupeKey, e);
            return false;
        }
    }
}
