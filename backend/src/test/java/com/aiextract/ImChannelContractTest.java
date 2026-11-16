package com.aiextract;

import com.aiextract.common.ImConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IM 渠道契约：支持范围、发布形态与会话隔离前缀。
 *
 * <p>合成 userId 决定会话记忆归属：飞书前缀必须保持历史值，否则老会话全部失联。</p>
 */
class ImChannelContractTest {

    @Test
    void supportsFeishuWecomDingtalkOnly() {
        assertTrue(ImConstants.isSupportedChannel("feishu"));
        assertTrue(ImConstants.isSupportedChannel("wecom"));
        assertTrue(ImConstants.isSupportedChannel("dingtalk"));
        assertFalse(ImConstants.isSupportedChannel("wechat"));
        assertFalse(ImConstants.isSupportedChannel(null));
        assertTrue(ImConstants.isSupportedChannel(" WECOM "));
    }

    @Test
    void onlyWecomAndDingtalkUseCredentialPublish() {
        assertTrue(ImConstants.isCredentialChannel("wecom"));
        assertTrue(ImConstants.isCredentialChannel("dingtalk"));
        assertFalse(ImConstants.isCredentialChannel("feishu"));
    }

    @Test
    void syntheticUserPrefixIsIsolatedPerChannel() {
        assertEquals("feishu-user:", ImConstants.userPrefix("feishu"));
        assertEquals("wecom-user:", ImConstants.userPrefix("wecom"));
        assertEquals("dingtalk-user:", ImConstants.userPrefix("dingtalk"));
        assertEquals("wecom-chat:", ImConstants.chatPrefix("wecom"));
        assertEquals("dingtalk-doc:", ImConstants.docPrefix("dingtalk"));
    }

    @Test
    void labelsCoverNewChannels() {
        assertEquals("飞书", ImConstants.channelLabel("feishu"));
        assertEquals("企业微信", ImConstants.channelLabel("wecom"));
        assertEquals("钉钉", ImConstants.channelLabel("dingtalk"));
    }
}
