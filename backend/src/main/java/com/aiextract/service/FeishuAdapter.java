package com.aiextract.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 飞书平台适配器
 *
 * <p>提供飞书平台的签名验证、消息解析和消息发送功能。
 * 遵循飞书开放平台v2版本的签名规范（HMAC-SHA256）。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@Component
public class FeishuAdapter {

    private static final String FIELD_CHALLENGE = "challenge";
    private static final String FIELD_EVENT = "event";
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_SENDER = "sender";
    private static final String FIELD_CONTENT = "content";

    private final ObjectMapper objectMapper;

    public FeishuAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 验证飞书回调签名
     *
     * <p>飞书签名格式：timestamp + "\n" + nonce + "\n" + encryptKey + "\n" + body</p>
     *
     * @param timestamp  时间戳
     * @param nonce      随机数
     * @param body       请求体
     * @param signature  签名
     * @param appSecret  应用密钥
     * @return true表示验证通过
     */
    public boolean verifySignature(String timestamp, String nonce, String body,
                                    String signature, String appSecret) {
        try {
            String signStr = timestamp + "\n" + nonce + "\n" + "" + "\n" + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec spec = new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(spec);
            byte[] signData = mac.doFinal(signStr.getBytes(StandardCharsets.UTF_8));
            String computed = Base64.getEncoder().encodeToString(signData);
            return computed.equals(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("飞书签名验证异常", e);
            return false;
        }
    }

    /**
     * 解析飞书回调消息，提取用户ID、群聊ID和消息文本
     *
     * @param payload 回调请求体JSON字符串
     * @return 解析结果Map（userId/chatId/chatType/text）
     */
    public FeishuMessage parseMessage(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);

            // 处理飞书"验证请求"（首次配置回调地址时）
            if (root.has(FIELD_CHALLENGE)) {
                return FeishuMessage.challenge(root.get(FIELD_CHALLENGE).asText());
            }

            JsonNode event = root.path(FIELD_EVENT);
            JsonNode message = event.path(FIELD_MESSAGE);
            JsonNode sender = event.path(FIELD_SENDER);

            String appId = event.path("app_id").asText();
            String chatType = message.path("chat_type").asText();
            String chatId = message.path("chat_id").asText();
            String userId = sender.path("sender_id").path("user_id").asText();
            String text = extractText(message);

            // 提取被@的用户ID列表
            String mentions = extractMentions(message);

            return FeishuMessage.builder()
                    .appId(appId)
                    .userId(userId)
                    .chatId(chatId)
                    .chatType(chatType)
                    .text(text)
                    .mentions(mentions)
                    .build();

        } catch (Exception e) {
            log.error("解析飞书消息失败", e);
            return FeishuMessage.builder().text("").build();
        }
    }

    /**
     * 从飞书消息体中提取文本内容
     */
    private String extractText(JsonNode message) {
        JsonNode content = message.path(FIELD_CONTENT);
        if (!content.isMissingNode()) {
            try {
                JsonNode parsed = objectMapper.readTree(content.asText());
                return parsed.path("text").asText("");
            } catch (Exception e) {
                return content.asText();
            }
        }
        return "";
    }

    /**
     * 从飞书消息体中提取@提及列表
     */
    private String extractMentions(JsonNode message) {
        JsonNode mentions = message.path("mentions");
        if (mentions.isArray() && !mentions.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode m : mentions) {
                if (!sb.isEmpty()) { sb.append(","); }
                sb.append(m.path("id").asText());
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * 构建飞书回复消息的JSON格式
     *
     * @param chatId  群聊ID或用户ID
     * @param content Markdown格式内容
     * @param replyTo 是否@回复提问者（群聊场景传入userId）
     * @return 飞书API消息体JSON字符串
     */
    public String buildReplyMessage(String chatId, String content, String replyTo) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"receive_id\":\"").append(chatId).append("\",");
            sb.append("\"msg_type\":\"interactive\",");
            sb.append("\"content\":\"{\\\"config\\\":{\\\"wide_screen_mode\\\":true},\\\"elements\\\":[{");

            if (replyTo != null && !replyTo.isEmpty()) {
                sb.append("\\\"tag\\\":\\\"at\\\",\\\"user_id\\\":\\\"").append(replyTo).append("\\\"");
                sb.append("},{");
            }

            String escaped = content.replace("\"", "\\\"").replace("\n", "\\n");
            sb.append("\\\"tag\\\":\\\"markdown\\\",\\\"content\\\":\\\"").append(escaped).append("\\\"");
            sb.append("}]}\"");
            sb.append("}");

            return sb.toString();
        } catch (Exception e) {
            log.error("构建飞书回复消息失败", e);
            return "{}";
        }
    }

    /**
     * 飞书解析的消息对象
     */
    @lombok.Builder
    @lombok.Getter
    @lombok.Setter
    public static class FeishuMessage {

        /** 飞书应用ID（用于多企业路由） */
        private String appId;

        /** 用户ID */
        private String userId;

        /** 群聊ID */
        private String chatId;

        /** 聊天类型：p2p/group */
        private String chatType;

        /** 消息文本 */
        private String text;

        /** @提及的用户ID列表 */
        private String mentions;

        /** 是否飞书验证请求 */
        private boolean isChallenge;

        /** 验证码（仅验证请求有效） */
        private String challengeCode;

        /**
         * 飞书验证请求
         */
        public static FeishuMessage challenge(String code) {
            return FeishuMessage.builder()
                    .isChallenge(true)
                    .challengeCode(code)
                    .build();
        }
    }
}
