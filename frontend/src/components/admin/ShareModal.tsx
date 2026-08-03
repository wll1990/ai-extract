'use client';

import { useEffect, useState, useRef } from 'react';
import QRCode from 'qrcode';
import { adminGetOrCreateShare, adminToggleShare, adminUpdateShareCode, adminCreateInternalShare, type SkillShareInfo } from '@/lib/api/admin';
import { copyToClipboard } from '@/lib/clipboard';

interface Props {
  skillId: string;
  ownerName: string;
  onClose: () => void;
  /** 自定义获取/生成对外分享（非 admin 场景使用） */
  getOrCreatePublic?: (skillId: string) => Promise<SkillShareInfo>;
  /** 自定义启停对外分享 */
  togglePublic?: (skillId: string, enabled: boolean) => Promise<SkillShareInfo>;
  /** 自定义获取/生成对内分享 */
  getOrCreateInternal?: (skillId: string) => Promise<SkillShareInfo>;
}

type Channel = 'public' | 'internal';

/**
 * 分身分享弹窗 — 对外分享 + 对内分享双通道。
 *
 * 定位在触发按钮下方（父容器需 position:relative）。
 * 链接 = 当前站点 origin + /s/{code}（对外）或 /i/{code}（对内）。
 */
export default function ShareModal({
  skillId, ownerName, onClose,
  getOrCreatePublic, togglePublic, getOrCreateInternal,
}: Props) {
  const doGetPublic = getOrCreatePublic || adminGetOrCreateShare;
  const doToggle = togglePublic || adminToggleShare;
  const doGetInternal = getOrCreateInternal || adminCreateInternalShare;

  const [channel, setChannel] = useState<Channel>('public');
  const [share, setShare] = useState<SkillShareInfo | null>(null);
  const [qrDataUrl, setQrDataUrl] = useState('');
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);
  const [loading, setLoading] = useState(true);
  const [toggling, setToggling] = useState(false);
  const [editCode, setEditCode] = useState('');
  const [codeSaving, setCodeSaving] = useState(false);
  const [codeMsg, setCodeMsg] = useState('');
  const panelRef = useRef<HTMLDivElement>(null);
  const [flipUp, setFlipUp] = useState(false);

  // 检测弹窗是否超出屏幕，超出则向上翻转
  useEffect(() => {
    if (!panelRef.current) return;
    const rect = panelRef.current.getBoundingClientRect();
    if (rect.bottom > window.innerHeight - 16) setFlipUp(true);
  }, [share, qrDataUrl, channel]);

  const shareUrl = share
    ? `${window.location.origin}/${channel === 'public' ? 's' : 'i'}/${share.shareCode}`
    : '';

  // 加载分享数据
  const loadShare = async (ch: Channel) => {
    setLoading(true); setError(''); setQrDataUrl('');
    try {
      if (ch === 'public') {
        setShare(await doGetPublic(skillId));
      } else {
        setShare(await doGetInternal(skillId));
      }
    } catch (e) {
      setError((e as Error)?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadShare('public'); }, [skillId]);

  // 切换 channel 时加载对应分享
  const switchChannel = (ch: Channel) => {
    if (ch === channel) return;
    setChannel(ch);
    loadShare(ch);
  };

  // 生成 QR 码
  useEffect(() => {
    if (!shareUrl) return;
    QRCode.toDataURL(shareUrl, { width: 200, margin: 2 })
      .then(setQrDataUrl)
      .catch(() => setQrDataUrl(''));
  }, [shareUrl]);

  // 点击外部关闭
  useEffect(() => {
    const handleClick = (e: MouseEvent) => {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) {
        onClose();
      }
    };
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [onClose]);

  const copy = async () => {
    const ok = await copyToClipboard(shareUrl);
    if (ok) {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } else {
      setError('复制失败，请手动选择链接复制');
    }
  };

  const toggle = async () => {
    if (!share || toggling) return;
    setToggling(true);
    try {
      setShare(await doToggle(skillId, !share.enabled));
    } catch (e) {
      setError((e as Error)?.message || '操作失败');
    } finally {
      setToggling(false);
    }
  };

  const saveCode = async () => {
    if (!share || !editCode.trim() || codeSaving) return;
    setCodeSaving(true); setCodeMsg('');
    try {
      const updated = await adminUpdateShareCode(skillId, editCode.trim());
      setShare(updated); setEditCode('');
      setCodeMsg('已保存');
      setTimeout(() => setCodeMsg(''), 2000);
    } catch (e) {
      setCodeMsg((e as Error)?.message || '保存失败');
    } finally {
      setCodeSaving(false);
    }
  };

  return (
    <div
      ref={panelRef}
      className={`absolute z-50 w-[380px] max-w-[calc(100vw-2rem)] rounded-xl border border-border bg-white shadow-xl ${flipUp ? 'bottom-full mb-2' : 'top-full mt-2'} right-0`}
    >
      {/* 小三角 */}
      <div className={`absolute right-6 w-3 h-3 rotate-45 bg-white border-l border-t border-border ${flipUp ? '-bottom-1.5' : '-top-1.5'}`} />

      {/* Header */}
      <div className="flex items-center justify-between px-5 pt-4 pb-2">
        <h3 className="text-sm font-semibold text-foreground">分享「{ownerName}」</h3>
        <button onClick={onClose} className="text-muted-foreground-2 hover:text-foreground text-sm" aria-label="关闭">✕</button>
      </div>

      {/* Tab 切换 */}
      <div className="mx-5 mb-3 flex rounded-lg bg-surface p-0.5">
        {([
          ['public', '对外分享'] as const,
          ['internal', '对内分享'] as const,
        ]).map(([k, label]) => (
          <button
            key={k}
            onClick={() => switchChannel(k)}
            className={`flex-1 rounded-md py-1.5 text-xs font-medium transition-colors ${
              channel === k ? 'bg-white text-foreground shadow-sm' : 'text-muted-foreground'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {/* 说明文字 */}
      <p className="mx-5 mb-3 text-[11px] text-muted-foreground-2">
        {channel === 'public'
          ? '任何人拿到链接即可对话（游客限 5 条/天）'
          : '仅本公司员工或平台登录用户可访问'}
      </p>

      {/* 错误 */}
      {error && (
        <div className="mx-5 mb-3 rounded-lg bg-red-50 px-3 py-2 text-xs text-red-500">{error}</div>
      )}

      {/* 加载中 */}
      {loading && (
        <div className="mx-5 mb-4 flex items-center justify-center py-6 text-xs text-muted-foreground">
          加载中...
        </div>
      )}

      {!loading && share && (
        <>
          {/* QR 码 */}
          <div className="flex justify-center mb-4">
            {qrDataUrl ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                src={qrDataUrl}
                alt="分享二维码"
                className={`h-[160px] w-[160px] rounded-lg border border-border ${share.enabled ? '' : 'opacity-30 grayscale'}`}
              />
            ) : (
              <div className="flex h-[160px] w-[160px] items-center justify-center rounded-lg border border-border text-xs text-muted-foreground-2">
                二维码生成中…
              </div>
            )}
          </div>

          {/* 链接 + 复制 */}
          <div className="mx-5 flex items-center gap-2 mb-4">
            <input
              readOnly
              value={shareUrl}
              className="h-9 min-w-0 flex-1 rounded-lg border border-border bg-surface px-3 text-xs text-muted-foreground outline-none"
            />
            <button
              onClick={copy}
              className="h-9 flex-none rounded-lg bg-primary px-3 text-xs font-semibold text-white hover:bg-primary-hover"
            >
              {copied ? '已复制 ✓' : '复制'}
            </button>
          </div>

          {/* 对外分享：自定义短码 + 开关 */}
          {channel === 'public' && (
            <>
              <div className="mx-5 mb-3 rounded-lg bg-surface px-3 py-2.5">
                <div className="text-xs font-medium text-foreground mb-1.5">自定义短码</div>
                <div className="flex items-center gap-1.5">
                  <span className="text-[10px] text-muted-foreground-2 shrink-0">/s/</span>
                  <input
                    value={editCode || share.shareCode}
                    onChange={e => { setEditCode(e.target.value); setCodeMsg(''); }}
                    placeholder="如 alibaba-sales"
                    className="h-8 min-w-0 flex-1 rounded-lg border border-border bg-white px-2.5 text-xs outline-none focus:border-primary"
                  />
                  <button
                    onClick={saveCode}
                    disabled={codeSaving || (!editCode.trim())}
                    className="h-8 flex-none rounded-lg bg-primary px-2.5 text-xs font-semibold text-white hover:bg-primary-hover disabled:opacity-50"
                  >
                    {codeSaving ? '...' : '保存'}
                  </button>
                </div>
                {codeMsg && (
                  <p className={`mt-1 text-[10px] ${codeMsg === '已保存' ? 'text-green-600' : 'text-red-500'}`}>{codeMsg}</p>
                )}
              </div>

              <div className="mx-5 mb-4 flex items-center justify-between rounded-lg bg-surface px-3 py-2.5">
                <div>
                  <div className="text-xs font-medium text-foreground">对外共享</div>
                  <div className="mt-0.5 text-[10px] text-muted-foreground-2">
                    {share.enabled ? '所有拿到链接的用户均可对话' : '已关闭，链接立即失效'}
                  </div>
                </div>
                <button
                  onClick={toggle}
                  disabled={toggling}
                  aria-label="共享开关"
                  className={`relative h-5 w-9 flex-none rounded-full transition-colors ${share.enabled ? 'bg-primary' : 'bg-border-strong'}`}
                >
                  <span className={`absolute top-0.5 h-4 w-4 rounded-full bg-white shadow-sm transition-all ${share.enabled ? 'left-[18px]' : 'left-0.5'}`} />
                </button>
              </div>
            </>
          )}
        </>
      )}
    </div>
  );
}
