import 'dotenv/config';
import express from 'express';
import { Bridge } from './bridge.js';
import { createManagers } from './registry.js';
import { verify } from './hmac.js';
import { startFeishuRegistration } from './channels/feishu.js';
import { SUPPORTED_CHANNELS, CHANNEL_FEISHU } from './constants.js';

const PORT = Number(process.env.PORT || 3010);
const SECRET = process.env.IM_BRIDGE_TOKEN || 'dev-im-bridge-token';
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080/api/v1';

const bridge = new Bridge(SECRET, BACKEND_URL);
const managers = createManagers(bridge);

const app = express();
// 捕获原始 body 用于 HMAC 验签
app.use(express.json({ verify: (req, _res, buf) => { req.rawBody = buf.toString(); } }));
// 企业微信回调是 XML，单独按文本收
app.use(express.text({ type: ['text/xml', 'application/xml'] }));

/** 后端 → 桥 的 HMAC 验签中间件 */
function auth(req, res, next) {
  const ts = req.headers['x-im-timestamp'];
  const nonce = req.headers['x-im-nonce'];
  const sig = req.headers['x-im-signature'];
  if (!verify(SECRET, ts, nonce, req.rawBody || '', sig)) {
    return res.status(401).json({ error: 'unauthorized' });
  }
  next();
}

app.get('/health', (_req, res) => {
  res.json({
    ok: true,
    channels: SUPPORTED_CHANNELS,
    bots: Object.fromEntries(SUPPORTED_CHANNELS.map((t) => [t, managers[t]?.bots?.size ?? 0])),
  });
});

/** 飞书扫码授权：生成二维码，授权完成后桥回调后端 /complete */
app.post('/register', auth, async (req, res) => {
  const { registerToken, name, description, avatar } = req.body || {};
  console.log(`[im-bridge] /register 收到请求: name=${name || 'AI分身'}, avatar=${avatar ? '(已提供)' : '(无)'}`);

  try {
    const result = await startFeishuRegistration({
      bridge, registerToken, name, description, avatar,
    });
    res.json(result);
  } catch (e) {
    console.error('[im-bridge] 飞书 registerApp 生成二维码失败:', e?.message || e);
    res.status(500).json({ error: e?.message || 'registerApp 启动失败' });
  }
});

/** 后端通知建立连接（按 channelType 分发） */
app.post('/connect', auth, async (req, res) => {
  const { channelType = CHANNEL_FEISHU, botId, appId, appSecret, config = {} } = req.body || {};
  const manager = managers[channelType];
  if (!manager) {
    return res.status(400).json({ error: `不支持的渠道：${channelType}` });
  }
  try {
    await manager.connect({ botId, appId, appSecret, config });
    res.json({ ok: true });
  } catch (e) {
    console.error(`[im-bridge] connect failed channelType=${channelType} bot=${botId}:`, e?.message || e);
    res.status(500).json({ error: e?.message || 'connect failed' });
  }
});

/** 后端通知断开连接 */
app.post('/disconnect', auth, async (req, res) => {
  const { channelType = CHANNEL_FEISHU, botId } = req.body || {};
  const manager = managers[channelType];
  if (!manager) {
    return res.status(400).json({ error: `不支持的渠道：${channelType}` });
  }
  try {
    await manager.disconnect(botId);
    res.json({ ok: true });
  } catch (e) {
    console.error(`[im-bridge] disconnect failed channelType=${channelType} bot=${botId}:`, e?.message || e);
    res.status(500).json({ error: e?.message || 'disconnect failed' });
  }
});

/**
 * 后端 → 桥：主动通知推送（培训提醒等）。
 * 多租户凭据由后端 im_channel 下发后桥内已持有，这里只按 botId 找连接。
 */
app.post('/internal/push', auth, async (req, res) => {
  const { channelType = CHANNEL_FEISHU, botId, externalUserId, chatId, title, content, link } = req.body || {};
  const manager = managers[channelType];
  if (!manager || typeof manager.pushToUser !== 'function') {
    return res.status(400).json({ error: `渠道不支持推送：${channelType}` });
  }
  try {
    await manager.pushToUser({ botId, externalUserId, chatId, title, content, link });
    res.json({ ok: true });
  } catch (e) {
    console.error(`[im-bridge] push failed channelType=${channelType} bot=${botId}:`, e?.message || e);
    res.status(500).json({ error: e?.message || 'push failed' });
  }
});

/** 企业微信回调 URL 验证（明文 echostr） */
app.get('/wecom/callback', (req, res) => {
  try {
    res.send(managers.wecom.handleVerify(req.query));
  } catch (e) {
    console.error('[im-bridge] 企业微信回调验证失败:', e?.message || e);
    res.status(401).send('invalid signature');
  }
});

/** 企业微信消息回调：5 秒内 ack，异步回复 */
app.post('/wecom/callback', (req, res) => {
  try {
    res.send(managers.wecom.handleCallback(req.query, req.body));
  } catch (e) {
    console.error('[im-bridge] 企业微信回调处理失败:', e?.message || e);
    res.status(400).send('invalid request');
  }
});

/** 优雅退出：断开所有长连接 */
function shutdown(signal) {
  console.log(`[im-bridge] 收到 ${signal}，断开全部连接`);
  Promise.allSettled(
    SUPPORTED_CHANNELS.flatMap((type) =>
      [...(managers[type]?.bots?.keys() || [])].map((botId) => managers[type].disconnect(botId))),
  ).finally(() => process.exit(0));
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

// 开机对账：逐渠道从后端拉取已发布 bot 并重建连接（桥无本地状态）
app.listen(PORT, async () => {
  console.log(`[im-bridge] listening on :${PORT}`);
  for (const type of SUPPORTED_CHANNELS) {
    try {
      const bots = await bridge.listActive(type);
      for (const bot of bots) {
        await managers[type]
          .connect({
            botId: bot.botId,
            appId: bot.appId,
            appSecret: bot.appSecret,
            config: bot.config || {},
          })
          .catch((e) => console.error(`[im-bridge] boot connect ${type}/${bot.botId} failed:`, e?.message || e));
      }
      console.log(`[im-bridge] ${type} boot reconcile done, ${bots.length} bot(s)`);
    } catch (e) {
      console.error(`[im-bridge] ${type} boot reconcile failed:`, e?.message || e);
    }
  }
});
