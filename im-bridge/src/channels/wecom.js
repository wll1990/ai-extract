import axios from 'axios';
import { XMLParser } from 'fast-xml-parser';
import { CHANNEL_WECOM, WECOM_MAX_BYTES } from '../constants.js';
import { decryptWecom, wecomSignature } from './wecom-crypto.js';

const TOKEN_URL = 'https://qyapi.weixin.qq.com/cgi-bin/gettoken';
const SEND_URL = 'https://qyapi.weixin.qq.com/cgi-bin/message/send';
const TOKEN_SAFETY_MS = 5 * 60 * 1000;

const parser = new XMLParser({ ignoreAttributes: true, trimValues: true });

/**
 * 企业微信自建应用适配器。
 *
 * 与飞书的差别：企微没有 WebSocket 机器人通道，消息靠「应用回调 URL」推送。
 * 桥因此扮演回调接收端：验签 → 解密 → 5 秒内 ack → 异步调后端 → message/send 回复。
 * 只支持单聊（应用会话）；群聊需要企微「智能机器人」协议，不在本期范围。
 */
export class WecomManager {
  constructor(bridge) {
    this.bridge = bridge;
    /** botId(corpId:agentId) -> entry */
    this.bots = new Map();
  }

  async connect({ botId, appId, appSecret, config = {} }) {
    if (this.bots.has(botId)) return;
    const corpId = config.corpId || appId;
    const agentId = String(config.agentId || '');
    if (!corpId || !agentId || !appSecret || !config.token || !config.encodingAesKey) {
      throw new Error('企业微信连接缺少 corpId/agentId/secret/token/encodingAesKey');
    }
    this.bots.set(botId, {
      botId,
      corpId,
      agentId,
      secret: appSecret,
      token: config.token,
      encodingAesKey: config.encodingAesKey,
      accessToken: null,
      accessTokenExpireAt: 0,
    });
    console.log(`[im-bridge] 企业微信已就绪 bot=${botId}（等待回调消息）`);
  }

  async disconnect(botId) {
    this.bots.delete(botId);
    console.log(`[im-bridge] 企业微信已断开 bot=${botId}`);
  }

  /** 回调 URL 验证（GET）：用 echostr 试各 bot 的 AESKey，命中即返回明文 */
  handleVerify(query) {
    const { msg_signature: signature, timestamp, nonce, echostr } = query || {};
    for (const bot of this.bots.values()) {
      try {
        if (wecomSignature({ token: bot.token, timestamp, nonce, encrypt: echostr }) !== signature) {
          continue;
        }
        return decryptWecom({
          encrypt: echostr,
          encodingAesKey: bot.encodingAesKey,
          corpId: bot.corpId,
        });
      } catch {
        /* 该 bot 不匹配，继续试下一个 */
      }
    }
    throw new Error('企业微信回调验证失败：没有匹配的应用');
  }

  /**
   * 接收消息（POST）：返回 "success" 作为 5 秒内 ack，真正的 AI 回复异步发送。
   */
  handleCallback(query, body) {
    const outer = parser.parse(String(body || ''))?.xml;
    if (!outer) {
      throw new Error('企业微信回调报文不是合法 XML');
    }
    const corpId = String(outer.ToUserName ?? '');
    const agentId = String(outer.AgentID ?? '');
    const encrypt = String(outer.Encrypt ?? '');
    const { msg_signature: signature, timestamp, nonce } = query || {};

    const bot = this.bots.get(`${corpId}:${agentId}`) || this.findByCorp(corpId);
    if (!bot) {
      throw new Error(`企业微信回调未匹配到已发布机器人：${corpId}:${agentId}`);
    }
    if (wecomSignature({ token: bot.token, timestamp, nonce, encrypt }) !== signature) {
      throw new Error('企业微信回调签名校验失败');
    }
    const inner = decryptWecom({
      encrypt,
      encodingAesKey: bot.encodingAesKey,
      corpId: bot.corpId,
    });
    const msg = parser.parse(inner)?.xml || {};

    // 先 ack 再处理：企微 5 秒不响应会重试，重试由后端 messageId 去重兜底
    setImmediate(() => {
      this.process(bot, msg).catch((e) =>
        console.error('[im-bridge] 企业微信消息处理失败:', e?.message || e));
    });
    return 'success';
  }

  findByCorp(corpId) {
    for (const bot of this.bots.values()) {
      if (bot.corpId === corpId) return bot;
    }
    return null;
  }

  async process(bot, msg) {
    const msgType = String(msg.MsgType ?? '');
    const fromUser = String(msg.FromUserName ?? '');
    if (!fromUser) return;
    const messageId = msg.MsgId ? String(msg.MsgId) : `${msg.CreateTime || Date.now()}:${fromUser}`;

    if (msgType !== 'text') {
      await this.sendText(bot, fromUser, '暂只支持文字消息，请发送文字');
      return;
    }
    const text = String(msg.Content ?? '').trim();
    if (!text) return;

    const contract = {
      channelType: CHANNEL_WECOM,
      botId: bot.botId,
      messageId,
      conversation: { id: fromUser, type: 'p2p' },
      sender: { id: fromUser, displayName: '' },
      message: { type: 'text', text },
      timestamp: Number(msg.CreateTime) || Math.floor(Date.now() / 1000),
    };
    const { text: answer, error } = await this.bridge.askMessage(contract);
    const reply = error || answer;
    if (!reply) return;
    await this.sendText(bot, fromUser, reply);
    console.log(`[im-bridge] 企业微信已回复 bot=${bot.botId} user=${fromUser} len=${reply.length}`);
  }

  async accessToken(bot) {
    if (bot.accessToken && Date.now() < bot.accessTokenExpireAt) {
      return bot.accessToken;
    }
    const res = await axios.get(TOKEN_URL, {
      params: { corpid: bot.corpId, corpsecret: bot.secret },
      timeout: 10000,
    });
    if (!res.data || res.data.errcode !== 0 || !res.data.access_token) {
      throw new Error(`企业微信获取 access_token 失败：${res.data?.errmsg || '未知错误'}`);
    }
    bot.accessToken = res.data.access_token;
    const expireSec = Number(res.data.expires_in) || 7200;
    bot.accessTokenExpireAt = Date.now() + expireSec * 1000 - TOKEN_SAFETY_MS;
    return bot.accessToken;
  }

  /** 后端通知推送：按 userid 发到应用会话 */
  async pushToUser({ botId, externalUserId, title, content, link }) {
    const bot = this.bots.get(botId);
    if (!bot) {
      throw new Error(`企业微信应用未就绪：${botId}`);
    }
    if (!externalUserId) {
      throw new Error('企业微信推送缺少 userid');
    }
    const text = [`【${title}】`, content || '', link ? `查看详情：${link}` : '']
      .filter(Boolean).join('\n');
    await this.sendText(bot, externalUserId, text);
  }

  async sendText(bot, toUser, text) {
    const token = await this.accessToken(bot);
    for (const chunk of splitByBytes(text, WECOM_MAX_BYTES)) {
      const res = await axios.post(
        SEND_URL,
        {
          touser: toUser,
          msgtype: 'text',
          agentid: Number(bot.agentId),
          text: { content: chunk },
        },
        { params: { access_token: token }, timeout: 15000 },
      );
      if (!res.data || res.data.errcode !== 0) {
        throw new Error(`企业微信发送失败：${res.data?.errmsg || '未知错误'}`);
      }
    }
  }
}

/** 按 UTF-8 字节数切片，避免中文超长被企微拒绝 */
export function splitByBytes(text, maxBytes) {
  const chunks = [];
  let current = '';
  let bytes = 0;
  for (const ch of String(text || '')) {
    const size = Buffer.byteLength(ch, 'utf8');
    if (bytes + size > maxBytes && current) {
      chunks.push(current);
      current = '';
      bytes = 0;
    }
    current += ch;
    bytes += size;
  }
  if (current) chunks.push(current);
  return chunks;
}
