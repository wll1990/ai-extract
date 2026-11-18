package com.aiextract.service.session;

/** Redis 与 DB 均不可用 —— 过滤器据此返回 503，而不是 401。 */
public class SessionUnavailableException extends RuntimeException {
    public SessionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
