import crypto from 'crypto';

/**
 * 与后端 ImBridgeAuth 对称的 HMAC-SHA256 签名/验签。
 * 签名串：`timestamp + "\n" + nonce + "\n" + body`。
 */

export function sign(secret, timestamp, nonce, body) {
  const data = `${timestamp}\n${nonce}\n${body || ''}`;
  return crypto.createHmac('sha256', secret).update(data, 'utf8').digest('base64');
}

/** 为出站请求生成签名头（桥 → 后端） */
export function signHeaders(secret, body) {
  const timestamp = String(Math.floor(Date.now() / 1000));
  const nonce = crypto.randomUUID().replace(/-/g, '');
  return {
    'X-Im-Timestamp': timestamp,
    'X-Im-Nonce': nonce,
    'X-Im-Signature': sign(secret, timestamp, nonce, body),
  };
}

/** 校验入站请求签名（后端 → 桥） */
export function verify(secret, timestamp, nonce, body, signature) {
  if (!timestamp || !nonce || !signature) return false;
  const expected = sign(secret, timestamp, nonce, body);
  const a = Buffer.from(expected);
  const b = Buffer.from(signature);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
