package com.aiextract.service.im;

import com.aiextract.model.ChatChunk;
import reactor.core.publisher.Flux;

/**
 * IM 平台聊天适配器 — 多端流式消息分发
 *
 * <p>各 IM 平台的差异：
 * <ul>
 *   <li><b>飞书</b> — 支持"渐进式消息"（发送后可编辑），可实现打字机效果</li>
 *   <li><b>企业微信</b> — 不支持消息编辑，需积攒完整回复后一次发送</li>
 *   <li><b>钉钉</b> — 同企业微信，不支持流式编辑</li>
 * </ul>
 *
 * <p>新增 IM 平台只需实现此接口，ChatStreamService 和 ChatChunk 保持不变。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-14
 */
public interface ImChatAdapter {
    /**
     * platform。
     * @return 字符串
     */
    String platform();
    /**
     * 发送流式回复到 IM 频道
     *
     * @param channelId  频道/会话 ID（IM 平台侧）
     * @param flux       ChatStreamService 返回的流
     * @param context    平台特有上下文（token、messageId 等）
     */
    void sendStreamingReply(String channelId, Flux<ChatChunk> flux, Object context);
}

