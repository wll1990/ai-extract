package com.aiextract.service.session;

/** create() 返回：明文 token 只返回给客户端一次，服务端不落明文。 */
public record CreatedSession(String token, SessionInfo info) {
}
