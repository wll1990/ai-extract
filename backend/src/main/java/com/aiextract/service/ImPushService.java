package com.aiextract.service;

import com.aiextract.config.ImBridgeAuth;
import com.aiextract.model.ImChannel;
import com.aiextract.model.ImUserBinding;
import com.aiextract.repository.ImChannelRepository;
import com.aiextract.repository.ImUserBindingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IM 通知推送 —— 站内信的「外发通道」。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>只在用户已绑定时推送；未绑定/渠道停用直接跳过，站内信仍是唯一真相；</li>
 *   <li>只发标题 + 摘要 + 站内链接，不发正文、客户隐私或成绩明细；</li>
 *   <li>失败只记日志，不影响业务事务（异步执行）。</li>
 * </ul>
 *
 * @author AI Extract Team
 * @since 2026-11-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImPushService {

    private final ImUserBindingRepository bindingRepository;
    private final ImChannelRepository channelRepository;
    private final ImBridgeAuth imBridgeAuth;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();

    @Value("${app.im.bridge-url:http://localhost:3010}")
    private String bridgeUrl;

    /** IM 里点击链接要能直达，站内相对路径统一补成完整地址 */
    @Value("${app.frontend-base-url:https://admin.loomforge.cn}")
    private String frontendBaseUrl;

    /** 通知类型白名单：只有与培训闭环相关的提醒才外发，避免把站内信全量倒进 IM */
    private static final List<String> PUSH_TYPES = List.of(
            "training_daily_drill",
            "training_pending_certification",
            "training_stale_review",
            "training_remediation",
            "training_deadline_extended",
            "training_review_result",
            "training_certified",
            "training_certification_revoked",
            "training_pending_review");

    public boolean supports(String type) {
        return type != null && PUSH_TYPES.contains(type);
    }

    /** 异步推给该用户已绑定的全部渠道；失败静默（只记日志） */
    @Async
    public void pushAsync(UUID userId, UUID companyId, String type, String title,
                          String content, String link) {
        if (userId == null || !supports(type)) {
            return;
        }
        List<ImUserBinding> bindings = bindingRepository.findByUserIdOrderByCreatedAtDesc(userId);
        for (ImUserBinding binding : bindings) {
            try {
                pushOne(binding, title, content, link);
            } catch (Exception e) {
                log.warn("IM 通知推送失败 userId={} channel={} type={}: {}",
                        userId, binding.getChannelType(), type, e.getMessage());
            }
        }
    }

    /** 管理端测试推送：明确返回结果，便于排查凭据/绑定问题 */
    public Map<String, Object> pushTest(UUID userId, String title, String content) {
        List<ImUserBinding> bindings = bindingRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (bindings.isEmpty()) {
            return Map.of("sent", 0, "reason", "该用户还没有绑定任何 IM 账号");
        }
        int sent = 0;
        List<String> errors = new java.util.ArrayList<>();
        for (ImUserBinding binding : bindings) {
            try {
                pushOne(binding, title, content, "/training");
                sent++;
            } catch (Exception e) {
                errors.add(binding.getChannelType() + ": " + e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", sent);
        result.put("errors", errors);
        return result;
    }

    private String absoluteLink(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        return link.startsWith("http://") || link.startsWith("https://")
                ? link : frontendBaseUrl.replaceAll("/$", "") + link;
    }

    private void pushOne(ImUserBinding binding, String title, String content, String link) throws Exception {
        ImChannel channel = channelRepository.findByBotId(binding.getBotId()).orElse(null);
        if (channel == null || !Boolean.TRUE.equals(channel.getEnabled())) {
            log.debug("IM 渠道未启用，跳过推送 botId={}", binding.getBotId());
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channelType", binding.getChannelType());
        payload.put("botId", binding.getBotId());
        payload.put("externalUserId", binding.getExternalUserId());
        payload.put("chatId", binding.getChatId());
        payload.put("title", title);
        payload.put("content", content);
        payload.put("link", absoluteLink(link));
        String body = objectMapper.writeValueAsString(payload);
        Map<String, String> headers = imBridgeAuth.signHeaders(body);
        restClient.post()
                .uri(bridgeUrl + "/internal/push")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> headers.forEach(h::set))
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
