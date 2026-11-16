package com.aiextract.config;

import com.aiextract.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 出站地址校验 — 防止企业配置的 AI BaseURL 被用于 SSRF。
 *
 * <p>规则：
 * <ul>
 *   <li>只允许 https（本地开发可通过 app.ai.allow-local-base-url=true 放行 localhost/环回）；</li>
 *   <li>解析域名后，拒绝环回、私网、链路本地、保留、组播地址；</li>
 *   <li>拒绝云元数据地址（169.254.169.254、metadata.google.internal）。</li>
 * </ul>
 *
 * <p>本地部署的模型（Ollama / vLLM）会命中私网或 http，此时走
 * {@link #validateModelEndpoint(String, boolean)}：只有主机名命中
 * {@code app.ai.local-model-hosts} 白名单才放行，白名单为空则一律拒绝。</p>
 *
 * <p>DNS 解析发生在保存/测试时，仍存在 DNS rebinding 风险，后续可加出站代理白名单。</p>
 */
@Slf4j
@Component
public class OutboundUrlValidator {

    private final boolean allowLocal;
    private final Set<String> localModelHosts;

    public OutboundUrlValidator(@Value("${app.ai.allow-local-base-url:false}") boolean allowLocal,
                                @Value("${app.ai.local-model-hosts:}") String localModelHosts) {
        this.allowLocal = allowLocal;
        this.localModelHosts = Arrays.stream(localModelHosts.split(","))
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public void validate(String rawUrl) {
        validateInternal(rawUrl, false);
    }

    /**
     * 企业 AI 端点校验。customBaseUrlAllowed=true 时允许 http/私网，
     * 但目标主机必须命中本地模型白名单。
     */
    public void validateModelEndpoint(String rawUrl, boolean customBaseUrlAllowed) {
        validateInternal(rawUrl, customBaseUrlAllowed);
    }

    private void validateInternal(String rawUrl, boolean allowPrivateTarget) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BusinessException(400, "Base URL 不能为空");
        }
        String value = rawUrl.trim();
        if (value.length() > 300) {
            throw new BusinessException(400, "Base URL 过长");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (Exception e) {
            throw new BusinessException(400, "Base URL 格式不正确");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException(400, "Base URL 缺少域名");
        }
        if (uri.getUserInfo() != null) {
            throw new BusinessException(400, "Base URL 不能包含用户信息");
        }
        if (!"https".equals(scheme) && !("http".equals(scheme) && allowLocal)) {
            if (!(allowPrivateTarget && "http".equals(scheme))) {
                throw new BusinessException(400, "Base URL 必须以 https:// 开头");
            }
            requireWhitelisted(host);
        }

        String lowerHost = host.toLowerCase(Locale.ROOT);
        if ("metadata.google.internal".equals(lowerHost) || "metadata".equals(lowerHost)) {
            throw new BusinessException(400, "不允许的地址");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new BusinessException(400, "Base URL 域名无法解析");
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                if (!allowPrivateTarget) {
                    throw new BusinessException(400, "Base URL 不允许指向内网或保留地址");
                }
                // 本地部署模型：必须显式白名单，避免企业管理员借此探测内网
                requireWhitelisted(host);
            }
        }
        log.debug("出站地址校验通过 host={} addresses={} allowPrivateTarget={}",
                host, addresses.length, allowPrivateTarget);
    }

    private void requireWhitelisted(String host) {
        String lowerHost = host.toLowerCase(Locale.ROOT);
        boolean ok = localModelHosts.stream()
                .anyMatch(entry -> lowerHost.equals(entry) || lowerHost.endsWith("." + entry));
        if (!ok) {
            throw new BusinessException(400,
                    "本地模型端点需在 app.ai.local-model-hosts 白名单内（当前：" + host + "）");
        }
    }

    private boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            // 本地开发允许环回，但仍禁止元数据/私网段
            return !(allowLocal && address.isLoopbackAddress());
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 100.64.0.0/10（运营商级 NAT）
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            // 0.0.0.0/8、192.0.0.0/24、198.18.0.0/15
            if (first == 0 || (first == 192 && second == 0)) {
                return true;
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return true;
            }
        }
        return false;
    }
}
