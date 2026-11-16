import { registerApp, createLarkChannel } from '@larksuite/channel';
import { CHANNEL_FEISHU, PRIVACY_NOTICE, CONV_DOC, DOC_MAX_CHARS, QR_EXPIRE_SECONDS } from '../constants.js';

/**
 * 飞书 channel 连接池 — botId → { channel, appId }。
 * 每个已发布 bot 一条 WebSocket 长连接；桥无本地凭据，凭据由后端下发。
 */
export class ChannelManager {
  constructor(bridge) {
    this.bridge = bridge;
    this.channels = new Map();
    this.greeted = new Set(); // 已发送过开场白卡片的单聊 chatId
  }

  /** 统一命名：/health 与优雅退出按 bots 遍历所有适配器 */
  get bots() {
    return this.channels;
  }

  async connect({ botId, appId, appSecret, openingMessage = '', recommendedQuestions = [] }) {
    if (this.channels.has(botId)) return;

    const channel = createLarkChannel({
      appId,
      appSecret,
      transport: 'websocket',
      // 群聊仅在被 @ 时回复；按 chat 串行，避免上下文错乱
      policy: { requireMention: true },
      safety: { chatQueue: { enabled: true } },
    });

    const entry = {
      channel, appId, appSecret, openingMessage, recommendedQuestions,
      botId, reconnecting: false,
    };
    channel.on('message', (msg) => this.handleMessage(entry, msg));
    channel.on('botAdded', (evt) => this.handleBotAdded(entry, evt));
    channel.on('cardAction', (evt) => this.handleCardAction(entry, evt));
    channel.on('comment', (evt) => this.handleComment(entry, evt));
    channel.on('error', (err) => {
      console.error(`[im-bridge] bot ${botId} error:`, err?.message || err);
      // 桥断开/鉴权抖动时自动重连，避免「published 但永远收不到消息」
      this.scheduleReconnect(botId, entry);
    });
    if (typeof channel.on === 'function') {
      try {
        channel.on('close', () => this.scheduleReconnect(botId, entry));
        channel.on('disconnect', () => this.scheduleReconnect(botId, entry));
      } catch { /* 库不支持该事件时忽略 */ }
    }
    await channel.connect();

    this.channels.set(botId, entry);
    console.log(`[im-bridge] connected bot ${botId}`);
  }

  async disconnect(botId) {
    const entry = this.channels.get(botId);
    if (!entry) return;
    entry.closedByUser = true;
    await entry.channel.disconnect().catch(() => {});
    this.channels.delete(botId);
    console.log(`[im-bridge] disconnected bot ${botId}`);
  }

  /**
   * 断线重连：15s 后重建 WebSocket。手动下线（closedByUser）不重连；
   * 重建失败会继续退避重试，直到成功或进程退出。
   */
  scheduleReconnect(botId, entry) {
    if (!entry || entry.closedByUser || entry.reconnecting) return;
    entry.reconnecting = true;
    const retry = async () => {
      try {
        await entry.channel.disconnect().catch(() => {});
      } catch { /* ignore */ }
      if (this.channels.get(botId) === entry) {
        this.channels.delete(botId);
      }
      if (entry.closedByUser) return;
      try {
        await this.connect({
          botId,
          appId: entry.appId,
          appSecret: entry.appSecret,
          openingMessage: entry.openingMessage,
          recommendedQuestions: entry.recommendedQuestions,
        });
        console.log(`[im-bridge] reconnected bot ${botId}`);
      } catch (e) {
        console.warn(`[im-bridge] reconnect bot ${botId} failed: ${e?.message || e}`);
        entry.reconnecting = false;
        this.scheduleReconnect(botId, entry);
      }
    };
    console.warn(`[im-bridge] bot ${botId} 连接异常，15s 后重连`);
    setTimeout(retry, 15000).unref?.();
  }

  /**
   * 后端通知推送：发到用户与机器人的单聊。
   * 必须有 chatId（p2p 会话 ID）；open_id 不能直接作为 channel.send 的目标。
   */
  async pushToUser({ botId, chatId, title, content, link }) {
    const entry = this.channels.get(botId);
    if (!entry) {
      throw new Error(`飞书 bot 未连接：${botId}`);
    }
    if (!chatId) {
      throw new Error('飞书推送缺少 chatId（用户需要先给机器人发一条消息完成绑定）');
    }
    const lines = [`**${title}**`, '', content || ''];
    if (link) {
      lines.push('', `[查看详情](${link})`);
    }
    await entry.channel.send(chatId, { markdown: lines.join('\n') });
  }

  /** 发送开场白 + 推荐问题卡片（可点击按钮）+ 隐私告知；无内容则跳过 */
  async sendGreeting(entry, chatId) {
    const elements = [];
    if (entry.openingMessage) {
      elements.push({ tag: 'markdown', content: entry.openingMessage });
    }
    if (entry.recommendedQuestions?.length) {
      if (elements.length) elements.push({ tag: 'hr' });
      elements.push({ tag: 'markdown', content: '💡 你可以试试问我：' });
      elements.push({
        tag: 'action',
        actions: entry.recommendedQuestions.map((q) => ({
          tag: 'button',
          text: { tag: 'plain_text', content: q },
          type: 'primary',
          value: { question: q },
        })),
      });
    }
    if (!elements.length) return;
    elements.push({
      tag: 'note',
      elements: [{ tag: 'plain_text', content: PRIVACY_NOTICE }],
    });

    await entry.channel.send(chatId, {
      card: { config: { wide_screen_mode: true }, elements },
    });
  }

  async handleMessage(entry, msg) {
    console.log(`[im-bridge] 收到消息: bot=${entry.appId}, msgId=${msg.messageId}, chat=${msg.chatId}(${msg.chatType}), sender=${msg.senderId}, text=${(msg.content || '').slice(0, 120)}`);

    // 单聊首条消息：主动发开场白 + 推荐问题卡片（降低冷启动门槛）
    if (msg.chatType === 'p2p' && !this.greeted.has(msg.chatId)) {
      this.greeted.add(msg.chatId);
      await this.sendGreeting(entry, msg.chatId)
        .catch((e) => console.error('[im-bridge] send p2p greeting failed:', e?.message || e));
    }

    const contract = {
      channelType: CHANNEL_FEISHU,
      botId: entry.appId,
      messageId: msg.messageId,
      conversation: { id: msg.chatId, type: msg.chatType },
      sender: { id: msg.senderId, displayName: msg.senderName },
      message: { type: msg.rawContentType || 'text', text: msg.content || '' },
      timestamp: msg.createTime,
    };

    const markdown = await this.askBackend(entry, contract);
    if (!markdown) {
      console.log(`[im-bridge] 空回复（去重命中或后端无内容），不回复: msgId=${msg.messageId}`);
      return;
    }

    try {
      await entry.channel.reply(msg, { markdown });
      console.log(`[im-bridge] 已回复: msgId=${msg.messageId}, len=${markdown.length}`);
    } catch (e) {
      console.error('[im-bridge] reply failed:', e?.message || e);
    }
  }

  /** 机器人被拉进群时，主动发开场白 + 推荐问题卡片 */
  async handleBotAdded(entry, evt) {
    try {
      await this.sendGreeting(entry, evt.chatId);
      console.log(`[im-bridge] 已发送入群引导卡片: bot=${entry.appId}, chat=${evt.chatId}`);
    } catch (e) {
      console.error('[im-bridge] send greeting failed:', e?.message || e);
    }
  }

  /** 云文档评论 @bot：读评论 + 文档 → 调分身 → 回复评论 */
  async handleComment(entry, evt) {
    if (!evt.mentionedBot) return;
    console.log(`[im-bridge] 文档评论: bot=${entry.appId}, file=${evt.fileToken}(${evt.fileType}), comment=${evt.commentId}`);

    try {
      const target = await entry.channel.comments.resolveTarget(evt.fileToken, evt.fileType);
      if (!target) {
        console.warn(`[im-bridge] 不支持的文档类型: ${evt.fileType}`);
        return;
      }
      const comment = await entry.channel.comments.fetch(target, evt.commentId);
      if (!comment) return;

      const docText = await this.readDocument(entry, target);
      if (!docText) {
        await entry.channel.comments.reply(target, evt.commentId, '抱歉，我暂时读不了这个文档的内容（仅支持 doc/docx 文档）');
        return;
      }

      const question = extractCommentText(comment);
      const full = buildDocMessage(question, comment.quote, docText);

      const contract = {
        channelType: CHANNEL_FEISHU,
        botId: entry.appId,
        messageId: `doc:${evt.commentId}`,
        conversation: { id: evt.fileToken, type: CONV_DOC },
        sender: { id: evt.operator?.openId || evt.operator?.userId || 'doc-user', displayName: evt.operator?.name || '' },
        message: { type: 'text', text: full },
        timestamp: Math.floor(Date.now() / 1000),
      };

      const answer = await this.askBackend(entry, contract);
      if (!answer) return;

      await entry.channel.comments.reply(target, evt.commentId, answer, { topLevel: comment.isWhole });
      console.log(`[im-bridge] 文档评论已回复: file=${evt.fileToken}, comment=${evt.commentId}`);
    } catch (e) {
      console.error('[im-bridge] handle comment failed:', e?.message || e);
    }
  }

  /** 读文档纯文本（raw_content 一次调用）；非 doc/docx 返回 null */
  async readDocument(entry, target) {
    if (target.fileType !== 'doc' && target.fileType !== 'docx') return null;
    try {
      const resp = await entry.channel.rawClient.docx.document.rawContent({
        path: { document_id: target.fileToken },
      });
      const content = resp?.data?.content || '';
      return content.length > DOC_MAX_CHARS ? content.slice(0, DOC_MAX_CHARS) + '\n…(文档过长已截断)' : content;
    } catch (e) {
      console.error('[im-bridge] read document failed:', e?.message || e);
      return null;
    }
  }

  /** 卡片按钮点击：把按钮值当作问题，后台异步处理并回复 */
  async handleCardAction(entry, evt) {
    const question = extractQuestion(evt?.action?.value);
    if (!question) return;
    console.log(`[im-bridge] 卡片按钮点击: bot=${entry.appId}, chat=${evt.chatId}, question=${question}`);

    // 立即回 toast，重活 detach 到后台（避免 Feishu 回调超时）
    this.processCardQuestion(entry, evt.chatId, question, evt.operator)
      .catch((e) => console.error('[im-bridge] process card question failed:', e?.message || e));
    return { toast: { type: 'info', content: '已收到，正在思考…' } };
  }

  async processCardQuestion(entry, chatId, question, operator) {
    const chatType = await this.resolveChatType(entry, chatId);
    const contract = {
      channelType: CHANNEL_FEISHU,
      botId: entry.appId,
      messageId: `card:${Date.now()}:${question}`,
      conversation: { id: chatId, type: chatType },
      sender: {
        id: operator?.openId || operator?.userId || 'card-user',
        displayName: operator?.name || '',
      },
      message: { type: 'text', text: question },
      timestamp: Math.floor(Date.now() / 1000),
    };

    const markdown = await this.askBackend(entry, contract);
    if (!markdown) return;
    try {
      await entry.channel.send(chatId, { markdown });
      console.log(`[im-bridge] 卡片问题已回复: chat=${chatId}, len=${markdown.length}`);
    } catch (e) {
      console.error('[im-bridge] card reply failed:', e?.message || e);
    }
  }

  /** 解析卡片点击所在会话类型（topic 归为 group），失败默认 p2p */
  async resolveChatType(entry, chatId) {
    try {
      const mode = await entry.channel.getChatMode(chatId);
      return mode === 'p2p' ? 'p2p' : 'group';
    } catch {
      return 'p2p';
    }
  }

  /** 调后端拿回复文本；返回 null 表示去重/空（不回复），异常时返回降级文案 */
  async askBackend(entry, contract) {
    try {
      const { text, error } = await this.bridge.askMessage(contract);
      if (error) return error;
      if (text) return text;
      return null;
    } catch (e) {
      console.error('[im-bridge] ask backend failed:', e?.message || e);
      return '系统繁忙，请稍后重试';
    }
  }
}

/** 从卡片按钮 value 里取出问题文本（兼容字符串与 { question } 两种形态） */
function extractQuestion(value) {
  if (typeof value === 'string' && value) return value;
  if (value && typeof value === 'object' && typeof value.question === 'string') return value.question;
  return '';
}

/** 从评论 replies 里提取纯文本 */
function extractCommentText(comment) {
  const texts = [];
  for (const reply of comment.replies || []) {
    for (const el of reply.content?.elements || []) {
      if (el.type === 'text_run' && el.text_run?.text) {
        texts.push(el.text_run.text);
      }
    }
  }
  return texts.join('\n').trim();
}

/** 组装「评论意图 + 引用原文 + 文档内容」消息 */
function buildDocMessage(commentText, quote, docText) {
  const parts = [];
  if (commentText) parts.push(`用户评论：${commentText}`);
  if (quote) parts.push(`选中的原文：${quote}`);
  parts.push(`文档内容：\n${docText}`);
  return parts.join('\n\n');
}

/**
 * 扫码授权：生成二维码，后台等待扫码完成并回调后端 /complete。
 * 返回 { qrUrl, expireIn }；授权结果通过后端状态机体现（管理端轮询机器人列表）。
 */
export async function startFeishuRegistration({ bridge, registerToken, name, description, avatar }) {
  let resolveQr;
  const qrReady = new Promise((resolve) => { resolveQr = resolve; });

  const appPreset = { name: name || 'AI分身', description: description || '' };
  if (avatar && typeof avatar === 'string' && avatar.startsWith('http')) {
    appPreset.avatar = avatar;
  }

  const registerPromise = registerApp({
    onQRCodeReady: ({ url, expireIn }) => {
      console.log(`[im-bridge] 飞书二维码已生成: expireIn=${expireIn}`);
      resolveQr({ url });
    },
    onStatusChange: (status) => {
      console.log(`[im-bridge] 飞书扫码授权状态: ${JSON.stringify(status)}`);
    },
    appPreset,
  });

  let qr;
  try {
    qr = await Promise.race([
      qrReady,
      new Promise((_, reject) => setTimeout(() => reject(new Error('registerApp qr timeout')), 30000)),
    ]);
  } catch {
    throw new Error('registerApp 启动失败');
  }

  registerPromise
    .then(({ client_id, client_secret }) => {
      console.log(`[im-bridge] 飞书扫码授权成功: client_id=${client_id}`);
      return bridge.complete(CHANNEL_FEISHU, registerToken, client_id, client_secret);
    })
    .then(() => console.log('[im-bridge] 飞书 /complete 回调后端成功'))
    .catch((err) => {
      const http = err?.response?.status;
      JSON.stringify(err?.response?.data ? { detail: err.response.data } : {});
      console.error(`[im-bridge] 飞书 registerApp/complete 失败: code=${err?.code || 'N/A'}${http ? `, http=${http}` : ''}, message=${err?.message || err}`);
    });

  return { qrUrl: qr.url, expireIn: QR_EXPIRE_SECONDS };
}
