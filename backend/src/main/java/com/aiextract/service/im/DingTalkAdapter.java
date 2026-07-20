package com.aiextract.service.im;

import com.aiextract.model.ChatChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 钉钉 Bot 适配器
 *
 * <p>钉钉机器人回调支持 Outgoing 和 Incoming 两种模式。
 * 同企业微信，不支持消息流式编辑，采用"积攒→一次发送"策略。</p>
 *
 * <p>接入步骤：
 * <ol>
 *   <li>钉钉开放平台 → 应用开发 → 机器人</li>
 *   <li>配置消息接收地址：https://your-domain/api/v1/im/dingtalk/callback</li>
 *   <li>在 application.yml 中配置 dingtalk.app-key / dingtalk.app-secret</li>
 * </ol>
 *
 * @author AI Extract Team
 * @since 2026-07-14
 */
@Slf4j
@Component
public class DingTalkAdapter implements ImChatAdapter {

    private static final String CHUNK_TYPE_CONTENT = "content";

    @Override
    public String platform() {
        return "dingtalk";
    }

    public DingTalkEvent parseEvent(String jsonBody) {
        log.debug("钉钉事件解析（待实现）");
        return new DingTalkEvent("", "", "");
    }

    @Override
    public void sendStreamingReply(String chatId, Flux<ChatChunk> flux, Object context) {
        log.info("钉钉回复（待实现）: chatId={}", chatId);
        final StringBuilder fullContent = new StringBuilder();
        flux.subscribe(
            chunk -> {
                if (CHUNK_TYPE_CONTENT.equals(chunk.getType()) && chunk.getContent() != null) {
                    fullContent.append(chunk.getContent());
                }
            },
            error -> log.error("钉钉流式响应异常: {}", error.getMessage()),
            () -> sendDingTalkMessage(chatId, fullContent.toString())
        );
    }

    private void sendDingTalkMessage(String chatId, String content) {
        // POST https://oapi.dingtalk.com/robot/send?access_token=TOKEN
        // body: {"msgtype":"text","text":{"content":"..."}}
        log.info("钉钉消息发送（待接入钉钉 API）: chatId={} len={}", chatId, content.length());
    }

    public record DingTalkEvent(String userId, String message, String conversationId) {}
}
