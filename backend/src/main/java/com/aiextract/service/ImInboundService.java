package com.aiextract.service;

import com.aiextract.common.ImConstants;
import com.aiextract.config.RolePermissions;
import com.aiextract.dto.ImMessageRequest;
import com.aiextract.dto.SkillChatRequest;
import com.aiextract.model.ChatChunk;
import com.aiextract.model.ImChannel;
import com.aiextract.repository.ImChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * IM 入站路由 — 去重 → bot_id 路由 → 内容安全 → 合成 userId → 统一 chat() 管线。
 *
 * <p>与 web 端共用 {@link ChatStreamService#chat}，区别仅在于：
 * <ul>
 *   <li>走 {@code /internal/im/message}（HMAC 鉴权，不进 JWT 网关）</li>
 *   <li>分身 ID 由后端从受信 {@code im_channel} 绑定解析，外部只传 botId</li>
 *   <li>userId 为合成 UUID（飞书用户无 user 表记录）</li>
 * </ul></p>
 *
 * @author AI Extract Team
 * @since 2026-08-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImInboundService {

    /** 已完成标记：流正常结束后才写入，保证失败消息可重投 */
    private static final String DEDUP_PREFIX = "im:dedup:";
    private static final Duration DEDUP_TTL = Duration.ofHours(1);
    /** 处理中锁：防止并发重复处理；桥调用失败时随 TTL 自动释放，可被重试 */
    private static final String DEDUP_LOCK_PREFIX = "im:dedup:lock:";
    private static final Duration DEDUP_LOCK_TTL = Duration.ofMinutes(5);
    private static final String RATE_PREFIX = "im:rate:";
    private static final DateTimeFormatter MINUTE_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final ImChannelRepository imChannelRepository;
    private final ChatStreamService chatStreamService;
    private final ImBindingService imBindingService;
    private final QueryGate queryGate;
    private final StringRedisTemplate redisTemplate;

    /** 每 bot 每分钟消息上限（-1 关闭，走 IM 独立限流，不占 H5 限流器） */
    @Value("${app.im.rate-limit.per-min:120}")
    private int imRatePerMin;

    /**
     * 接收归一化消息，返回统一 ChatChunk 流（由 Controller 包 SseAdapter）。
     */
    public Flux<ChatChunk> receive(ImMessageRequest req) {
        // ── 1. 去重（at-least-once，先到先得） ──
        if (req.getBotId() == null || req.getMessageId() == null) {
            return Flux.just(ChatChunk.error("缺少 botId 或 messageId"), ChatChunk.done());
        }
        String dedupKey = DEDUP_PREFIX + req.getBotId() + ":" + req.getMessageId();
        String lockKey = DEDUP_LOCK_PREFIX + req.getBotId() + ":" + req.getMessageId();
        Boolean first;
        try {
            // 已完成 → 直接跳过；处理中 → 视为并发重复；只有正常结束才写「已完成」
            if (Boolean.TRUE.equals(redisTemplate.hasKey(dedupKey))) {
                log.info("重复消息已忽略 botId={} messageId={}", req.getBotId(), req.getMessageId());
                return Flux.just(ChatChunk.done());
            }
            first = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", DEDUP_LOCK_TTL);
        } catch (Exception e) {
            log.warn("IM 去重 Redis 异常，fail-open 放行: {}", e.getMessage());
            first = Boolean.TRUE;
        }
        if (Boolean.FALSE.equals(first)) {
            log.info("消息处理中或重复，忽略 botId={} messageId={}", req.getBotId(), req.getMessageId());
            return Flux.just(ChatChunk.done());
        }

        // ── 2. 路由：bot_id → 渠道（可只做通知，不绑分身） ──
        ImChannel channel = imChannelRepository.findByBotId(req.getBotId()).orElse(null);
        if (channel == null || !Boolean.TRUE.equals(channel.getEnabled())
                || !ImConstants.STATUS_PUBLISHED.equals(channel.getStatus())) {
            log.warn("IM 渠道未配置/未启用 botId={}", req.getBotId());
            return Flux.just(ChatChunk.error("机器人未配置或已下线"), ChatChunk.done());
        }

        // ── 2.5 限流（每 bot 每分钟上限，fail-open） ──
        if (imRatePerMin > 0 && !allowRate(req.getBotId())) {
            log.warn("IM 限流触发 botId={}", req.getBotId());
            return Flux.just(ChatChunk.error("提问太频繁，请稍后再试"), ChatChunk.done());
        }

        // ── 3. 非文本消息降级 ──
        ImMessageRequest.Message message = req.getMessage();
        if (message == null || message.getText() == null || message.getText().isBlank()
                || !ImConstants.MSG_TYPE_TEXT.equals(message.getType())) {
            return Flux.just(ChatChunk.error("暂只支持文字消息，请发送文字"), ChatChunk.done());
        }

        // ── 3.5 绑定命令：绑定 123456（通知型机器人也走这里，不要求绑定分身） ──
        String senderId = req.getSender() == null ? null : req.getSender().getId();
        String conversationId = req.getConversation() == null ? null : req.getConversation().getId();
        String displayName = req.getSender() == null ? null : req.getSender().getDisplayName();
        var bindReply = imBindingService.handleBindCommand(channel, message.getText(),
                senderId, conversationId, displayName);
        if (bindReply.isPresent()) {
            return Flux.just(ChatChunk.content(bindReply.get()), ChatChunk.done());
        }
        // 已绑定用户每发一条消息都刷新最后活跃时间与 chatId
        imBindingService.touch(channel, senderId, conversationId);

        if (channel.getSkillId() == null) {
            return Flux.just(ChatChunk.content(
                    "这个机器人用于培训提醒。要接收提醒，请在平台「我的培训」生成绑定码，"
                            + "然后在这里发送：绑定 123456"),
                    ChatChunk.done());
        }
        UUID skillId = channel.getSkillId();

        // ── 4. 内容安全（QueryGate Layer 1+2） ──
        String blocked = queryGate.auditSimple(message.getText());
        if (blocked != null) {
            return Flux.just(ChatChunk.error(blocked), ChatChunk.done());
        }

        // ── 5. 合成 userId + 构建请求 ──
        UUID syntheticUserId = resolveSyntheticUserId(req);
        SkillChatRequest chatReq = new SkillChatRequest();
        chatReq.setMessage(message.getText());
        chatReq.setChannel(req.getChannelType());

        // ── 6. 统一 chat() 管线（单聊多轮 / 群聊每问独立） ──
        // 流正常结束才标记「已完成」；中断/异常只释放处理中锁，允许桥重投
        return chatStreamService.chat(skillId, chatReq, syntheticUserId, RolePermissions.EMPLOYEE)
                .doOnComplete(() -> markProcessed(dedupKey, lockKey))
                .doOnError(e -> releaseProcessingLock(lockKey, e));
    }

    private void markProcessed(String dedupKey, String lockKey) {
        try {
            redisTemplate.opsForValue().set(dedupKey, "1", DEDUP_TTL);
        } catch (Exception e) {
            log.warn("IM 去重标记写入失败（消息已回复，可能重复）: {}", e.getMessage());
        } finally {
            try {
                redisTemplate.delete(lockKey);
            } catch (Exception ignored) {
                // TTL 兜底
            }
        }
    }

    private void releaseProcessingLock(String lockKey, Throwable error) {
        log.warn("IM 消息处理失败，释放去重锁以便重投: {}", error.getMessage());
        try {
            redisTemplate.delete(lockKey);
        } catch (Exception ignored) {
            // TTL 兜底
        }
    }

    /**
     * 合成稳定 userId（确定性 UUID，无映射表）。
     * <p>单聊按人记忆；群聊每问独立（不注入他人上下文）。后续「引用续聊」用 thread rootId 锚定。</p>
     */
    private UUID resolveSyntheticUserId(ImMessageRequest req) {
        String type = req.getConversation() != null ? req.getConversation().getType() : ImConstants.CONV_P2P;
        String convId = req.getConversation() != null ? req.getConversation().getId() : "";
        String senderId = req.getSender() != null ? req.getSender().getId() : "";
        String channelType = req.getChannelType();
        String key;
        if (ImConstants.CONV_P2P.equals(type)) {
            key = ImConstants.userPrefix(channelType) + senderId;
        } else if (ImConstants.CONV_DOC.equals(type)) {
            key = ImConstants.docPrefix(channelType) + convId + ":" + senderId;
        } else {
            key = ImConstants.chatPrefix(channelType) + convId + ":" + req.getMessageId();
        }
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /** 每 bot 每分钟限流（Redis INCR 固定窗口，fail-open） */
    private boolean allowRate(String botId) {
        try {
            String key = RATE_PREFIX + botId + ":" + LocalDateTime.now().format(MINUTE_FMT);
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofMinutes(1));
            }
            return count == null || count <= imRatePerMin;
        } catch (Exception e) {
            return true; // Redis 异常 fail-open
        }
    }
}
