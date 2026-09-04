import { createLarkChannel } from '@larksuite/channel';
import { CHANNEL_FEISHU, PRIVACY_NOTICE, CONV_DOC, DOC_MAX_CHARS } from './constants.js';

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

    const entry = { channel, appId, openingMessage, recommendedQuestions };
    channel.on('message', (msg) => this.handleMessage(entry, msg));
    channel.on('botAdded', (evt) => this.handleBotAdded(entry, evt));
    channel.on('cardAction', (evt) => this.handleCardAction(entry, evt));
    channel.on('comment', (evt) => this.handleComment(entry, evt));
    channel.on('error', (err) =>
      console.error(`[feishu-bot] bot ${botId} error:`, err?.message || err));
    await channel.connect();

    this.channels.set(botId, entry);
    console.log(`[feishu-bot] connected bot ${botId}`);
  }

  async disconnect(botId) {
    const entry = this.channels.get(botId);
    if (!entry) return;
    await entry.channel.disconnect().catch(() => {});
    this.channels.delete(botId);
    console.log(`[feishu-bot] disconnected bot ${botId}`);
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
    console.log(`[feishu-bot] 收到消息: bot=${entry.appId}, msgId=${msg.messageId}, chat=${msg.chatId}(${msg.chatType}), sender=${msg.senderId}, text=${(msg.content || '').slice(0, 120)}`);

    // 单聊首条消息：主动发开场白 + 推荐问题卡片（降低冷启动门槛）
    if (msg.chatType === 'p2p' && !this.greeted.has(msg.chatId)) {
      this.greeted.add(msg.chatId);
      await this.sendGreeting(entry, msg.chatId)
        .catch((e) => console.error('[feishu-bot] send p2p greeting failed:', e?.message || e));
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
      console.log(`[feishu-bot] 空回复（去重命中或后端无内容），不回复: msgId=${msg.messageId}`);
      return;
    }

    try {
      await entry.channel.reply(msg, { markdown });
      console.log(`[feishu-bot] 已回复: msgId=${msg.messageId}, len=${markdown.length}`);
    } catch (e) {
      console.error('[feishu-bot] reply failed:', e?.message || e);
    }
  }

  /** 机器人被拉进群时，主动发开场白 + 推荐问题卡片 */
  async handleBotAdded(entry, evt) {
    try {
      await this.sendGreeting(entry, evt.chatId);
      console.log(`[feishu-bot] 已发送入群引导卡片: bot=${entry.appId}, chat=${evt.chatId}`);
    } catch (e) {
      console.error('[feishu-bot] send greeting failed:', e?.message || e);
    }
  }

  /** 云文档评论 @bot：读评论 + 文档 → 调分身 → 回复评论 */
  async handleComment(entry, evt) {
    if (!evt.mentionedBot) return;
    console.log(`[feishu-bot] 文档评论: bot=${entry.appId}, file=${evt.fileToken}(${evt.fileType}), comment=${evt.commentId}`);

    try {
      const target = await entry.channel.comments.resolveTarget(evt.fileToken, evt.fileType);
      if (!target) {
        console.warn(`[feishu-bot] 不支持的文档类型: ${evt.fileType}`);
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
      console.log(`[feishu-bot] 文档评论已回复: file=${evt.fileToken}, comment=${evt.commentId}`);
    } catch (e) {
      console.error('[feishu-bot] handle comment failed:', e?.message || e);
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
      console.error('[feishu-bot] read document failed:', e?.message || e);
      return null;
    }
  }

  /** 卡片按钮点击：把按钮值当作问题，后台异步处理并回复 */
  async handleCardAction(entry, evt) {
    const question = extractQuestion(evt?.action?.value);
    if (!question) return;
    console.log(`[feishu-bot] 卡片按钮点击: bot=${entry.appId}, chat=${evt.chatId}, question=${question}`);

    // 立即回 toast，重活 detach 到后台（避免 Feishu 回调超时）
    this.processCardQuestion(entry, evt.chatId, question, evt.operator)
      .catch((e) => console.error('[feishu-bot] process card question failed:', e?.message || e));
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
      console.log(`[feishu-bot] 卡片问题已回复: chat=${chatId}, len=${markdown.length}`);
    } catch (e) {
      console.error('[feishu-bot] card reply failed:', e?.message || e);
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
      console.error('[feishu-bot] ask backend failed:', e?.message || e);
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
