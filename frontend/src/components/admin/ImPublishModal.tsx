'use client';
import { useEffect, useRef, useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import { publishToChannel, listImBots, type ImPublishResult } from '@/lib/api/im';

type Channel = 'feishu' | 'wecom' | 'dingtalk';

const CHANNELS: Array<{ id: Channel; label: string; icon: string; hint: string }> = [
  { id: 'feishu', label: '飞书', icon: '🕊️', hint: '扫码授权，支持单聊、群聊和云文档评论' },
  { id: 'wecom', label: '企业微信', icon: '💼', hint: '自建应用，单聊问答；回调需公网 HTTPS 指向 IM 桥' },
  { id: 'dingtalk', label: '钉钉', icon: '📌', hint: 'Stream 模式长连接，不需要公网回调地址' },
];

interface Props {
  skillId: string;
  skillName: string;
  skillAvatar?: string;
  onClose: () => void;
  onPublished?: () => void;
}

type Phase = 'config' | 'scanning' | 'expired' | 'done' | 'error';

/**
 * 发布到 IM 渠道弹窗。
 * 飞书：填机器人资料 → 生成二维码 → 扫码轮询；
 * 企微/钉钉：填应用凭据 → 后端校验后立即绑定。
 */
export default function ImPublishModal({ skillId, skillName, skillAvatar, onClose, onPublished }: Props) {
  const [channel, setChannel] = useState<Channel>('feishu');
  const [phase, setPhase] = useState<Phase>('config');
  const [name, setName] = useState(skillName || '');
  const [description, setDescription] = useState('');
  const [avatar, setAvatar] = useState(skillAvatar || '');
  const [result, setResult] = useState<ImPublishResult | null>(null);
  const [remain, setRemain] = useState(0);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const pollRef = useRef<number | null>(null);

  // 企业微信自建应用
  const [wecom, setWecom] = useState({ corpId: '', agentId: '', secret: '', token: '', encodingAesKey: '' });
  // 钉钉 Stream
  const [dingtalk, setDingtalk] = useState({ appKey: '', appSecret: '', robotCode: '' });

  const stopPoll = () => {
    if (pollRef.current) {
      window.clearInterval(pollRef.current);
      pollRef.current = null;
    }
  };

  const startPoll = () => {
    stopPoll();
    pollRef.current = window.setInterval(async () => {
      try {
        const bots = await listImBots();
        const found = bots.find(
          (b) => b.skillId === skillId && b.channelType === channel && b.status === 'published',
        );
        if (found) {
          stopPoll();
          setPhase('done');
          onPublished?.();
        }
      } catch {
        /* 轮询失败忽略，下轮重试 */
      }
    }, 3000);
  };

  const missingCredential = (): string | null => {
    if (channel === 'wecom') {
      if (!wecom.corpId.trim()) return '请填写企业 ID（corpId）';
      if (!wecom.agentId.trim()) return '请填写 AgentId';
      if (!wecom.secret.trim()) return '请填写应用 Secret';
      if (!wecom.token.trim()) return '请填写回调 Token';
      if (wecom.encodingAesKey.trim().length !== 43) return 'EncodingAESKey 必须是 43 位';
    }
    if (channel === 'dingtalk') {
      if (!dingtalk.appKey.trim()) return '请填写 AppKey';
      if (!dingtalk.appSecret.trim()) return '请填写 AppSecret';
    }
    return null;
  };

  const submit = async () => {
    if (!name.trim() || submitting) return;
    const missing = missingCredential();
    if (missing) {
      setError(missing);
      setPhase('error');
      return;
    }
    setSubmitting(true);
    setError('');
    try {
      const credentials: Record<string, string> | undefined =
        channel === 'wecom'
          ? { ...wecom }
          : channel === 'dingtalk'
            ? { appKey: dingtalk.appKey, appSecret: dingtalk.appSecret, robotCode: dingtalk.robotCode }
            : undefined;
      const r = await publishToChannel(channel, skillId, {
        name: name.trim(),
        description: description.trim(),
        avatar: avatar.trim(),
        credentials,
      });
      setResult(r);
      if (channel === 'feishu' && r.qrUrl) {
        setRemain(r.expireIn || 600);
        setPhase('scanning');
        startPoll();
      } else {
        setPhase('done');
        onPublished?.();
      }
    } catch (e) {
      setError((e as Error).message || '发布失败');
      setPhase('error');
    } finally {
      setSubmitting(false);
    }
  };

  useEffect(() => stopPoll, []);

  // 倒计时
  useEffect(() => {
    if (phase !== 'scanning') return;
    const t = window.setInterval(() => {
      setRemain((prev) => {
        if (prev <= 1) {
          window.clearInterval(t);
          setPhase('expired');
          stopPoll();
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
    return () => window.clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase]);

  const mm = Math.floor(remain / 60);
  const ss = String(remain % 60).padStart(2, '0');
  const active = CHANNELS.find((c) => c.id === channel)!;

  return (
    <div className="fixed inset-0 z-50 bg-black/40 flex items-center justify-center" onClick={onClose}>
      <div className="bg-white rounded-2xl p-6 w-full max-w-md mx-4 shadow-xl text-center max-h-[90vh] overflow-y-auto" onClick={(e) => e.stopPropagation()}>
        <h3 className="text-lg font-bold mb-1">发布到 IM 渠道</h3>
        <p className="text-xs text-muted-foreground mb-4">{skillName}</p>

        {phase === 'config' && (
          <div className="space-y-3 text-left">
            <div>
              <label className="block text-sm font-medium mb-1">渠道</label>
              <div className="flex gap-2">
                {CHANNELS.map((c) => (
                  <button
                    key={c.id}
                    type="button"
                    onClick={() => { setChannel(c.id); setError(''); }}
                    className={`flex-1 rounded-lg border px-2 py-2 text-sm ${
                      channel === c.id ? 'border-primary bg-primary/10 text-primary font-medium' : 'border-gray-200 text-gray-600'
                    }`}
                  >
                    {c.icon} {c.label}
                  </button>
                ))}
              </div>
              <p className="mt-1 text-xs text-gray-500">{active.hint}</p>
            </div>

            <div>
              <label className="block text-sm font-medium mb-1">机器人名称 *</label>
              <input value={name} onChange={(e) => setName(e.target.value)}
                className="w-full rounded-lg border px-3 py-2 text-sm" placeholder="员工在 IM 里搜索时看到的名称" />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1">机器人描述</label>
              <input value={description} onChange={(e) => setDescription(e.target.value)}
                className="w-full rounded-lg border px-3 py-2 text-sm" placeholder="如：华东销售部销冠经验问答" />
            </div>
            {channel === 'feishu' && (
              <div>
                <label className="block text-sm font-medium mb-1">机器人头像 URL（可选，需公网地址）</label>
                <input value={avatar} onChange={(e) => setAvatar(e.target.value)}
                  className="w-full rounded-lg border px-3 py-2 text-sm" placeholder="https://.../avatar.png" />
              </div>
            )}

            {channel === 'wecom' && (
              <div className="space-y-2 rounded-lg border border-gray-200 p-3">
                <p className="text-xs text-gray-500">
                  企业微信管理后台 → 应用管理 → 自建应用。回调 URL 填
                  <code className="mx-1 rounded bg-gray-100 px-1">https://你的域名/wecom/callback</code>
                  （需反代到 IM 桥 3010）。
                </p>
                <Field label="企业 ID（corpId）" value={wecom.corpId} onChange={(v) => setWecom({ ...wecom, corpId: v })} />
                <Field label="AgentId" value={wecom.agentId} onChange={(v) => setWecom({ ...wecom, agentId: v })} placeholder="1000002" />
                <Field label="应用 Secret" value={wecom.secret} onChange={(v) => setWecom({ ...wecom, secret: v })} secret />
                <Field label="回调 Token" value={wecom.token} onChange={(v) => setWecom({ ...wecom, token: v })} />
                <Field label="EncodingAESKey（43 位）" value={wecom.encodingAesKey} onChange={(v) => setWecom({ ...wecom, encodingAesKey: v })} secret />
              </div>
            )}

            {channel === 'dingtalk' && (
              <div className="space-y-2 rounded-lg border border-gray-200 p-3">
                <p className="text-xs text-gray-500">
                  钉钉开放平台 → 应用 → 机器人，选择 Stream 模式并发布。凭据即应用的 ClientID/ClientSecret。
                </p>
                <Field label="AppKey（ClientID）" value={dingtalk.appKey} onChange={(v) => setDingtalk({ ...dingtalk, appKey: v })} />
                <Field label="AppSecret（ClientSecret）" value={dingtalk.appSecret} onChange={(v) => setDingtalk({ ...dingtalk, appSecret: v })} secret />
                <Field label="RobotCode（可选）" value={dingtalk.robotCode} onChange={(v) => setDingtalk({ ...dingtalk, robotCode: v })} />
              </div>
            )}

            <button onClick={submit} disabled={!name.trim() || submitting}
              className="w-full rounded-lg bg-primary text-white px-4 py-2 text-sm disabled:opacity-40">
              {submitting ? '发布中…' : channel === 'feishu' ? '生成二维码' : `发布到${active.label}`}
            </button>
          </div>
        )}

        {phase === 'scanning' && result?.qrUrl && (
          <>
            <div className="mx-auto w-52 h-52 bg-white border rounded-xl p-2">
              <QRCodeSVG value={result.qrUrl} size={192} />
            </div>
            <p className="mt-3 text-sm">用飞书 App 扫码授权</p>
            <p className="text-xs text-muted-foreground mt-1">
              剩余 <span className="font-semibold text-primary">{mm}:{ss}</span>
            </p>
            <a href={result.qrUrl} target="_blank" rel="noreferrer" className="text-xs text-primary underline">
              在浏览器中打开
            </a>
          </>
        )}

        {phase === 'expired' && (
          <>
            <p className="text-sm text-muted-foreground py-6">二维码已过期</p>
            <button onClick={submit} className="rounded-lg bg-primary text-white px-4 py-2 text-sm">重新生成</button>
          </>
        )}

        {phase === 'done' && (
          <>
            <p className="text-4xl my-4">✅</p>
            <p className="text-sm font-medium">已发布到{active.label}</p>
            <p className="text-xs text-muted-foreground mt-1">
              {channel === 'feishu'
                ? '飞书员工现在可以单聊或在群聊 @ 此分身'
                : channel === 'wecom'
                  ? '员工可在企业微信应用会话里直接提问'
                  : '员工可在钉钉单聊或群里 @ 此机器人提问'}
            </p>
          </>
        )}

        {phase === 'error' && (
          <>
            <p className="text-sm text-danger py-6">{error}</p>
            <button onClick={() => { setPhase('config'); setError(''); }} className="rounded-lg bg-primary text-white px-4 py-2 text-sm">返回修改</button>
          </>
        )}

        <button onClick={onClose} className="mt-4 text-sm text-muted-foreground block w-full">关闭</button>
      </div>
    </div>
  );
}

function Field({
  label, value, onChange, placeholder, secret,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  secret?: boolean;
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs text-gray-600">{label}</span>
      <input
        type={secret ? 'password' : 'text'}
        autoComplete="off"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full rounded border border-gray-300 px-2 py-1.5 text-sm"
      />
    </label>
  );
}
