import axios from 'axios';
import { signHeaders } from './hmac.js';

/**
 * 后端桥接客户端 — 桥 → 后端（HMAC 签名），并解析后端 SSE 回复。
 */
export class Bridge {
  constructor(secret, backendUrl, bridgeId = process.env.BRIDGE_ID || 'default') {
    this.secret = secret;
    this.backendUrl = backendUrl.replace(/\/$/, '');
    // 多桥部署时区分身份：后端只下发本桥负责的 bot（避免 appSecret 全量下发）
    this.bridgeId = bridgeId;
  }

  bridgeHeaders() {
    return { 'X-Bridge-Id': this.bridgeId };
  }

  /** 转发消息到后端，返回 { text, error }（SSE 已聚合成全文） */
  async askMessage(contract) {
    const body = JSON.stringify(contract);
    const headers = { 'Content-Type': 'application/json', ...signHeaders(this.secret, body) };
    const res = await axios.post(`${this.backendUrl}/internal/im/message`, body, {
      headers,
      timeout: 200000, // 长于后端 chat 流 180s 超时
      responseType: 'text',
      transformResponse: [(data) => data], // 拿原始 SSE 文本，不做 JSON 解析
    });
    return parseSse(res.data);
  }

  /** 扫码完成回调（飞书桥 → 后端） */
  async complete(channelType, registerToken, appId, appSecret) {
    const body = JSON.stringify({ registerToken, appId, appSecret });
    const headers = {
      'Content-Type': 'application/json',
      ...signHeaders(this.secret, body),
      ...this.bridgeHeaders(),
    };
    await axios.post(`${this.backendUrl}/internal/im/${channelType}/complete`, body, {
      headers,
      timeout: 20000,
    });
  }

  /** 开机对账：拉取需连接的 bot 列表（含解密后凭据） */
  async listActive(channelType) {
    const headers = { ...signHeaders(this.secret, ''), ...this.bridgeHeaders() };
    const res = await axios.get(`${this.backendUrl}/internal/im/${channelType}/active`, {
      headers,
      timeout: 20000,
    });
    return Array.isArray(res.data) ? res.data : [];
  }
}

/** 解析后端 SseAdapter 输出的 SSE 文本 → { text, error } */
function parseSse(raw) {
  let text = '';
  let error = null;
  const lines = String(raw || '').split('\n');
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed.startsWith('data:')) continue;
    const data = trimmed.slice(5).trim();
    if (!data) continue;
    try {
      const obj = JSON.parse(data);
      if (obj.type === 'content' && obj.content) text += obj.content;
      else if (obj.type === 'error') error = obj.message || 'AI服务暂时不可用';
      // done / meta / source / warning / heartbeat 忽略
    } catch {
      /* 忽略无法解析的行 */
    }
  }
  return { text, error };
}
