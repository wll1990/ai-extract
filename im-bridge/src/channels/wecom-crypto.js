import crypto from 'crypto';

/**
 * 企业微信回调加解密（官方协议：AES-256-CBC + PKCS7(32) + Base64）。
 *
 * 明文结构：random(16) + msgLen(4, 大端) + msg + receiveId(corpId)
 * 签名：sha1(sort([token, timestamp, nonce, encrypt]).join(''))
 */

export function wecomSignature({ token, timestamp, nonce, encrypt }) {
  const raw = [token || '', timestamp || '', nonce || '', encrypt || ''].sort().join('');
  return crypto.createHash('sha1').update(raw, 'utf8').digest('hex');
}

function aesKey(encodingAesKey) {
  const key = Buffer.from(`${encodingAesKey || ''}=`, 'base64');
  if (key.length !== 32) {
    throw new Error('EncodingAESKey 长度非法（应为 43 位）');
  }
  return key;
}

export function decryptWecom({ encrypt, encodingAesKey, corpId }) {
  const key = aesKey(encodingAesKey);
  const iv = key.subarray(0, 16);
  const decipher = crypto.createDecipheriv('aes-256-cbc', key, iv);
  decipher.setAutoPadding(false);
  let buf = Buffer.concat([
    decipher.update(Buffer.from(encrypt, 'base64')),
    decipher.final(),
  ]);

  const pad = buf[buf.length - 1];
  if (pad < 1 || pad > 32) {
    throw new Error('企业微信密文填充非法');
  }
  buf = buf.subarray(0, buf.length - pad);

  const msgLen = buf.readUInt32BE(16);
  const msg = buf.subarray(20, 20 + msgLen).toString('utf8');
  const receiveId = buf.subarray(20 + msgLen).toString('utf8');
  if (corpId && receiveId !== corpId) {
    throw new Error(`企业微信 receiveId 不匹配：${receiveId}`);
  }
  return msg;
}

export function encryptWecom({ msg, encodingAesKey, corpId }) {
  const key = aesKey(encodingAesKey);
  const iv = key.subarray(0, 16);
  const random = crypto.randomBytes(16);
  const len = Buffer.alloc(4);
  len.writeUInt32BE(Buffer.byteLength(msg, 'utf8'), 0);
  const raw = Buffer.concat([random, len, Buffer.from(msg, 'utf8'), Buffer.from(corpId, 'utf8')]);
  const mod = raw.length % 32;
  const padLen = mod === 0 ? 32 : 32 - mod;
  const padded = Buffer.concat([raw, Buffer.alloc(padLen, padLen)]);

  const cipher = crypto.createCipheriv('aes-256-cbc', key, iv);
  cipher.setAutoPadding(false);
  return Buffer.concat([cipher.update(padded), cipher.final()]).toString('base64');
}
