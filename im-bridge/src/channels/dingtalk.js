import axios from 'axios';
import { DWClient, TOPIC_ROBOT } from 'dingtalk-stream';
import { CHANNEL_DINGTALK, DINGTALK_MAX_CHARS } from '../constants.js';

/**
 * 钉钉 Stream 模式适配器。
 *
 * 用 appKey/appSecret 建立 WebSocket 长连接，不需要公网回调地址；
 * 收到机器人消息后 3 秒内 ack（socketCallBackResponse），再用 sessionWebhook 异步回复。
 */
export class DingtalkManager {
  constructor(bridge) {
    this.bridge = bridge;
    /** botId(appKey) -> entry */
    this.bots = new Map();
  }

  async connect({ botId, appId, appSecret }) {
    if (this.bots.has(botId)) return;
    if (!appId || !appSecret) {
      throw new Error('钉钉连接缺少 appKey/appSecret');
    }
    const client = new DWClient({
      clientId: appId,
      clientSecret: appSecret,
      keepAlive: true,
      debug: false,
    });
    client.registerCallbackListener(TOPIC_ROBOT, (down) => {
      this.handleRobotMessage(botId, down).catch((e) =>
        console.error('[im-bridge] 钉钉消息处理失败:', e?.message || e));
    });
    await client.connect();
    this.bots.set(botId, {
      client, botId, appId, appSecret, accessToken: null, accessTokenExpireAt: 0,
    });
    console.log(`[im-bridge] 钉钉 Stream 已连接 bot=${botId}`);
  }

  async disconnect(botId) {
    const entry = this.bots.get(botId);
    if (!entry) return;
    try {
      entry.client.disconnect();
    } catch {
      /* 关闭失败不影响状态清理 */
    }
    this.bots.delete(botId);
    console.log(`[im-bridge] 钉钉 Stream 已断开 bot=${botId}`);
  }

  async handleRobotMessage(botId, down) {
    const entry = this.bots.get(botId);
    // 先 ack，避免钉钉 60 秒后重投同一消息
    try {
      entry?.client.socketCallBackResponse(down.headers?.messageId, { status: 'SUCCESS' });
    } catch {
      /* 已断连时忽略 */
    }

    let payload;
    try {
      payload = JSON.parse(down.data);
    } catch {
      console.warn(`[im-bridge] 钉钉消息体不是合法 JSON bot=${botId}`);
      return;
    }

    if (payload.msgtype !== 'text') {
      await this.replyByWebhook(payload, '暂只支持文字消息，请发送文字');
      return;
    }
    const text = String(payload.text?.content || '').trim();
    if (!text) return;

    const contract = {
      channelType: CHANNEL_DINGTALK,
      botId,
      messageId: payload.msgId || `${Date.now()}:${payload.senderStaffId || payload.senderId}`,
      conversation: {
        id: payload.conversationId,
        // 钉钉：1=单聊，2=群聊
        type: String(payload.conversationType) === '1' ? 'p2p' : 'group',
      },
      sender: {
        id: payload.senderStaffId || payload.senderId,
        displayName: payload.senderNick || '',
      },
      message: { type: 'text', text },
      timestamp: Math.floor((payload.createAt || Date.now()) / 1000),
    };

    const { text: answer, error } = await this.bridge.askMessage(contract);
    const reply = error || answer;
    if (!reply) return;
    await this.replyByWebhook(payload, reply);
    console.log(`[im-bridge] 钉钉已回复 bot=${botId} conv=${payload.conversationId} len=${reply.length}`);
  }

  /** sessionWebhook 有效期约 1.5 小时，直接用消息自带地址回复最省事 */
  /** 应用 access_token（机器人主动推送用；Stream 长连接不提供发送通道） */
  async accessToken(entry) {
    if (entry.accessToken && Date.now() < entry.accessTokenExpireAt) {
      return entry.accessToken;
    }
    const res = await axios.post(
      'https://api.dingtalk.com/v1.0/oauth2/accessToken',
      { appKey: entry.appId, appSecret: entry.appSecret },
      { timeout: 10000 },
    );
    const token = res.data?.accessToken;
    if (!token) {
      throw new Error(`钉钉获取 access_token 失败：${res.data?.message || '未知错误'}`);
    }
    entry.accessToken = token;
    entry.accessTokenExpireAt = Date.now()
      + (Number(res.data.expireIn) || 7200) * 1000 - 5 * 60 * 1000;
    return token;
  }

  /** 后端通知推送：机器人单聊发文本 */
  async pushToUser({ botId, externalUserId, title, content, link }) {
    const entry = this.bots.get(botId);
    if (!entry) {
      throw new Error(`钉钉 bot 未连接：${botId}`);
    }
    if (!externalUserId) {
      throw new Error('钉钉推送缺少 userId');
    }
    const token = await this.accessToken(entry);
    const text = [`【${title}】`, content || '', link ? `查看详情：${link}` : '']
      .filter(Boolean).join('\n');
    const res = await axios.post(
      'https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend',
      {
        robotCode: entry.appId,
        userIds: [externalUserId],
        msgKey: 'sampleText',
        msgParam: JSON.stringify({ content: text }),
      },
      { headers: { 'x-acs-dingtalk-access-token': token }, timeout: 15000 },
    );
    if (res.data?.processQueryKey === undefined && res.data?.invalidStaffIdList?.length) {
      throw new Error('钉钉推送失败：用户不在应用可见范围');
    }
  }

  async replyByWebhook(payload, text) {
    const url = payload.sessionWebhook;
    if (!url) {
      console.warn('[im-bridge] 钉钉消息缺少 sessionWebhook，无法回复');
      return;
    }
    for (const chunk of chunkText(text, DINGTALK_MAX_CHARS)) {
      await axios.post(
        url,
        { msgtype: 'text', text: { content: chunk } },
        { timeout: 15000 },
      );
    }
  }
}

export function chunkText(text, maxChars) {
  const value = String(text || '');
  const chunks = [];
  for (let i = 0; i < value.length; i += maxChars) {
    chunks.push(value.slice(i, i + maxChars));
  }
  return chunks;
}
