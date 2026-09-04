import 'dotenv/config';
import express from 'express';
import { registerApp } from '@larksuite/channel';
import { Bridge } from './bridge.js';
import { ChannelManager } from './channel-manager.js';
import { verify } from './hmac.js';
import { QR_EXPIRE_SECONDS } from './constants.js';

const PORT = Number(process.env.PORT || 3010);
const SECRET = process.env.IM_BRIDGE_TOKEN || 'dev-im-bridge-token';
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080/api/v1';

const bridge = new Bridge(SECRET, BACKEND_URL);
const manager = new ChannelManager(bridge);

const app = express();
// 捕获原始 body 用于 HMAC 验签
app.use(express.json({ verify: (req, _res, buf) => { req.rawBody = buf.toString(); } }));

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

app.get('/health', (_req, res) => res.json({ ok: true }));

/** 发起扫码授权：返回二维码，后台等待扫码完成后回调后端 complete */
app.post('/register', auth, async (req, res) => {
  const { registerToken, name, description, avatar } = req.body;
  console.log(`[feishu-bot] /register 收到请求: name=${name || 'AI分身'}, avatar=${avatar ? '(已提供)' : '(无)'}, token=${registerToken}`);

  let resolveQr;
  const qrReady = new Promise((r) => { resolveQr = r; });

  const appPreset = { name: name || 'AI分身', description: description || '' };
  if (avatar && typeof avatar === 'string' && avatar.startsWith('http')) {
    appPreset.avatar = avatar;
  }

  const registerPromise = registerApp({
    onQRCodeReady: ({ url, expireIn }) => {
      console.log(`[feishu-bot] 二维码已生成: expireIn=${expireIn}`);
      resolveQr({ url, expireIn });
    },
    onStatusChange: (status) => {
      console.log(`[feishu-bot] 扫码授权状态: ${JSON.stringify(status)}`);
    },
    appPreset,
  });

  let qr;
  try {
    qr = await Promise.race([
      qrReady,
      new Promise((_, rej) => setTimeout(() => rej(new Error('registerApp qr timeout')), 30000)),
    ]);
  } catch {
    console.error('[feishu-bot] registerApp 生成二维码超时/失败');
    return res.status(500).json({ error: 'registerApp 启动失败' });
  }

  res.json({ qrUrl: qr.url, expireIn: QR_EXPIRE_SECONDS });

  registerPromise
    .then(({ client_id, client_secret }) => {
      console.log(`[feishu-bot] 扫码授权成功: client_id=${client_id}`);
      return bridge.complete(registerToken, client_id, client_secret);
    })
    .then(() => console.log('[feishu-bot] /complete 回调后端成功'))
    .catch((err) => {
      const http = err?.response?.status;
      const detail = err?.response?.data ? JSON.stringify(err.response.data) : '';
      console.error(
        `[feishu-bot] registerApp/complete 失败: code=${err?.code || 'N/A'}${http ? `, http=${http}` : ''}, message=${err?.message || err}${detail ? `, detail=${detail}` : ''}`,
      );
    });
});

/** 后端通知建立连接 */
app.post('/connect', auth, async (req, res) => {
  const { botId, appId, appSecret, openingMessage, recommendedQuestions } = req.body;
  try {
    await manager.connect({ botId, appId, appSecret, openingMessage, recommendedQuestions });
    res.json({ ok: true });
  } catch (e) {
    console.error('[feishu-bot] connect failed:', e?.message || e);
    res.status(500).json({ error: 'connect failed' });
  }
});

/** 后端通知断开连接 */
app.post('/disconnect', auth, async (req, res) => {
  const { botId } = req.body;
  await manager.disconnect(botId);
  res.json({ ok: true });
});

// 开机对账：从后端拉取全部已发布 bot 并重建连接（桥无本地状态）
app.listen(PORT, async () => {
  console.log(`[feishu-bot] listening on :${PORT}`);
  try {
    const bots = await bridge.listActive();
    for (const bot of bots) {
      if (!bot.appSecret) {
        console.warn(`[feishu-bot] skip bot ${bot.botId}: missing appSecret`);
        continue;
      }
      await manager.connect({
        botId: bot.botId,
        appId: bot.appId,
        appSecret: bot.appSecret,
        openingMessage: bot.openingMessage,
        recommendedQuestions: bot.recommendedQuestions,
      }).catch((e) => console.error(`[feishu-bot] boot connect ${bot.botId} failed:`, e?.message || e));
    }
    console.log(`[feishu-bot] boot reconcile done, ${bots.length} bot(s)`);
  } catch (e) {
    console.error('[feishu-bot] boot reconcile failed:', e?.message || e);
  }
});
