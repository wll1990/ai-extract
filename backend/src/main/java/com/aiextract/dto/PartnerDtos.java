package com.aiextract.dto;

/** 三方 H5 接入的请求/响应体（嵌套 record，Jackson 直接绑定）。 */
public final class PartnerDtos {

    private PartnerDtos() {
    }

    public record CodeRequest(String externalUserId, String userName, String deviceId) {
    }

    public record CodeResponse(String code, int expiresIn) {
    }

    public record ExchangeRequest(String appId, String code) {
    }

    public record ExchangeResponse(String token, int expiresIn, String userId,
                                   String nickname, String externalUserId) {
    }

    public record RevokeAllRequest(String externalUserId) {
    }
}
