package com.aiextract.service.im;

import com.aiextract.model.ChatChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 企业微信 Bot 适配器
 *
 * <p>企业微信不支持消息编辑接口，采用"积攒→一次发送"策略。
 * 未来如企业微信支持流式消息，可改为渐进式发送模式。</p>
 *
 * <p>接入步骤：
 * <ol>
 *   <li>企业微信管理后台 → 应用管理 → 自建应用 → 接收消息 API</li>
 *   <li>配置回调 URL：https://your-domain/api/v1/im/wecom/callback</li>
 *   <li>配置 Token 和 EncodingAESKey</li>
 *   <li>在 application.yml 中配置 wecom.corp-id / wecom.secret</li>
 * </ol>
 *
 * @author AI Extract Team
 * @since 2026-07-14
 */
@Slf4j
@Component
public class WeComAdapter implements ImChatAdapter {

    private static final String CHUNK_TYPE_CONTENT = "content";

    @Override
    public String platform() {
        return "wecom";
    }

    /** 企微消息 XML 协议解析 — TODO: 实现企微回调解密和消息提取 */
    public WeComEvent parseEvent(String xmlBody) {
        // 企微回调消息体是加密 XML（AES + EncodingAESKey）
        // 需要解密后才能拿到明文消息
        log.debug("企微事件解析（待实现）");
        return new WeComEvent("", "", "");
    }

    @Override
    public void sendStreamingReply(String chatId, Flux<ChatChunk> flux, Object context) {
        // 企微策略：积攒完整回复后通过 /cgi-bin/message/send 发送
        // 不支持流式编辑，等 flux 完成后再一次发送
        log.info("企微回复（待实现）: chatId={}", chatId);
        final StringBuilder fullContent = new StringBuilder();
        flux.subscribe(
            chunk -> {
                if (CHUNK_TYPE_CONTENT.equals(chunk.getType()) && chunk.getContent() != null) {
                    fullContent.append(chunk.getContent());
                }
            },
            error -> log.error("企微流式响应异常: {}", error.getMessage()),
            () -> sendWeComMessage(chatId, fullContent.toString())
        );
    }

    private void sendWeComMessage(String chatId, String content) {
        // POST https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=TOKEN
        // body: {"touser":"...", "msgtype":"text", "agentid":..., "text":{"content":"..."}}
        log.info("企微消息发送（待接入企微 API）: chatId={} len={}", chatId, content.length());
    }

    public record WeComEvent(String userId, String message, String agentId) {}
}
