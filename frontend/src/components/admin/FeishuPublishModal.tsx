'use client';

import { useEffect, useRef, useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import { publishFeishu, listImBots, type FeishuPublishResult } from '@/lib/api/im';

interface Props {
  skillId: string;
  skillName: string;
  skillAvatar?: string;
  onClose: () => void;
  onPublished?: () => void;
}

type Phase = 'config' | 'scanning' | 'expired' | 'done' | 'error';

/**
 * 发布到飞书弹窗 — 两步状态机：
 * ① 配置表单（名称/描述/头像，预填分身资料）→ ② 二维码 + 倒计时 → 成功/过期/失败。
 */
export default function FeishuPublishModal({ skillId, skillName, skillAvatar, onClose, onPublished }: Props) {
  const [phase, setPhase] = useState<Phase>('config');
  const [name, setName] = useState(skillName || '');
  const [description, setDescription] = useState('');
  const [avatar, setAvatar] = useState(skillAvatar || '');
  const [result, setResult] = useState<FeishuPublishResult | null>(null);
  const [remain, setRemain] = useState(0);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const pollRef = useRef<number | null>(null);

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
        const found = bots.find((b) => b.skillId === skillId && b.status === 'published');
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

  const submit = async () => {
    if (!name.trim() || submitting) return;
    setSubmitting(true);
    setError('');
    try {
      const r = await publishFeishu(skillId, { name: name.trim(), description: description.trim(), avatar: avatar.trim() });
      setResult(r);
      setRemain(r.expireIn || 600);
      setPhase('scanning');
      startPoll();
    } catch (e) {
      setError((e as Error).message || '发起发布失败');
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

  return (
    <div className="fixed inset-0 z-50 bg-black/40 flex items-center justify-center" onClick={onClose}>
      <div className="bg-white rounded-2xl p-6 w-full max-w-sm mx-4 shadow-xl text-center" onClick={(e) => e.stopPropagation()}>
        <h3 className="text-lg font-bold mb-1">发布到飞书</h3>
        <p className="text-xs text-muted-foreground mb-4">{skillName}</p>

        {phase === 'config' && (
          <div className="space-y-3 text-left">
            <div>
              <label className="block text-sm font-medium mb-1">机器人名称 *</label>
              <input value={name} onChange={(e) => setName(e.target.value)}
                className="w-full rounded-lg border px-3 py-2 text-sm" placeholder="员工在飞书搜索时看到的名称" />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1">机器人描述</label>
              <input value={description} onChange={(e) => setDescription(e.target.value)}
                className="w-full rounded-lg border px-3 py-2 text-sm" placeholder="如：华东销售部销冠经验问答" />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1">机器人头像 URL（可选，需公网地址）</label>
              <input value={avatar} onChange={(e) => setAvatar(e.target.value)}
                className="w-full rounded-lg border px-3 py-2 text-sm" placeholder="https://.../avatar.png" />
            </div>
            <button onClick={submit} disabled={!name.trim() || submitting}
              className="w-full rounded-lg bg-primary text-white px-4 py-2 text-sm disabled:opacity-40">
              {submitting ? '生成中…' : '生成二维码'}
            </button>
          </div>
        )}

        {phase === 'scanning' && result && (
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
            <p className="text-sm font-medium">已发布到飞书</p>
            <p className="text-xs text-muted-foreground mt-1">飞书员工现在可以单聊或在群聊 @ 此分身</p>
          </>
        )}

        {phase === 'error' && (
          <>
            <p className="text-sm text-danger py-6">{error}</p>
            <button onClick={() => setPhase('config')} className="rounded-lg bg-primary text-white px-4 py-2 text-sm">返回修改</button>
          </>
        )}

        <button onClick={onClose} className="mt-4 text-sm text-muted-foreground block w-full">关闭</button>
      </div>
    </div>
  );
}
