import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'crypto';

import { decryptWecom, encryptWecom, wecomSignature } from '../src/channels/wecom-crypto.js';
import { splitByBytes } from '../src/channels/wecom.js';
import { chunkText } from '../src/channels/dingtalk.js';

/** 43 位合法 Base64 字符 = 32 字节 AES Key */
const AES_KEY = 'abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG';
const CORP_ID = 'ww1234567890abcdef';

test('企业微信加解密可往返，且校验 corpId', () => {
  const msg = '<xml><Content><![CDATA[价格太贵了怎么办？]]></Content></xml>';
  const encrypted = encryptWecom({ msg, encodingAesKey: AES_KEY, corpId: CORP_ID });
  const plain = decryptWecom({ encrypt: encrypted, encodingAesKey: AES_KEY, corpId: CORP_ID });
  assert.equal(plain, msg);

  assert.throws(
    () => decryptWecom({ encrypt: encrypted, encodingAesKey: AES_KEY, corpId: 'ww-other' }),
    /receiveId 不匹配/,
  );
});

test('企业微信签名与官方算法一致（字典序拼接后 sha1）', () => {
  const token = 'QDG6eK';
  const timestamp = '1409659813';
  const nonce = '1372623149';
  const encrypt = 'RypEvHKD8QQKFhvQ6QleEB4J58tiPdvo+rtK1I9qca6aM/wvqnLSV5zEPeusUiX5L5X/0lWfrf0QADHHhGd3QczcdCUpj911L3vg3W/sYYvuJTs3TUUkSUXxaccAS0qhxchrRYt66wiSpGLYL42aM6A8dTT+6k4aSknmPj48kzJs8qLjvd4Xgpue06DOdnLxAUHzM6+kDZ+HMZfJYuR+LtwGc2hgf5gsijff0ekUNXZiqATP7PF5mZxZ3Izoun1s4zG4LUMnvw2r+KqCKIw+3IQH03v+BCA9nMELNqbSf6tiWSrXJB3LAVGUcallcrw8V2t9EL4EhzJWrQUax5wLVMNS0+rUPA3k22Ncx4XXZS9o0MBH27Bo6BpNelZpS1ut6bAKUEhsF5SQ==';

  const signature = wecomSignature({ token, timestamp, nonce, encrypt });
  assert.equal(signature, '86aa7a182f7d4c3d53972b9b894fd5163509ea4a');
  // 参数顺序不影响结果（内部按字典序排序）
  assert.equal(wecomSignature({ encrypt, nonce, timestamp, token }), signature);
});

test('按字节切片不破坏中文，且每片不超上限', () => {
  const text = '价格'.repeat(800);
  const chunks = splitByBytes(text, 2000);
  assert.ok(chunks.length > 1);
  for (const chunk of chunks) {
    assert.ok(Buffer.byteLength(chunk, 'utf8') <= 2000);
  }
  assert.equal(chunks.join(''), text);
});

test('钉钉长文本按字符切片', () => {
  const text = 'a'.repeat(9000);
  const chunks = chunkText(text, 4000);
  assert.deepEqual(chunks.map((c) => c.length), [4000, 4000, 1000]);
  assert.equal(chunks.join(''), text);
});

test('非法 EncodingAESKey 直接拒绝', () => {
  assert.throws(
    () => encryptWecom({ msg: 'x', encodingAesKey: 'short', corpId: CORP_ID }),
    /EncodingAESKey/,
  );
});

// 防止 crypto 未使用导致 lint 报错（保留给未来的随机性断言）
void crypto;
