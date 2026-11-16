package com.aiextract.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * 客户端 IP 解析 — 只在请求来自可信反代（环回/内网）时才采信 X-Forwarded-For。
 *
 * <p>直接相信公网客户端发来的 XFF 会让攻击者随意伪造 IP，绕过按 IP 的登录限流。</p>
 */
@Slf4j
@Component
public class ClientIpResolver {

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remote = request.getRemoteAddr();
        if (isTrustedProxy(remote)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String first = xff.split(",")[0].trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }
        return remote == null ? "unknown" : remote;
    }

    /** 反代同机或同内网部署；公网直连的请求不采信转发头 */
    private boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
