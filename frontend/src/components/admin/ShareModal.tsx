'use client';

import { useEffect, useState } from 'react';
import QRCode from 'qrcode';
import { adminGetOrCreateShare, adminToggleShare, type SkillShareInfo } from '@/lib/api/admin';

interface Props {
  skillId: string;
  ownerName: string;
  onClose: () => void;
  /** 自定义获取/生成分享（默认走 admin API；skill 页传 skill 端点可让 owner 也能用） */
  getOrCreate?: (skillId: string) => Promise<SkillShareInfo>;
  /** 自定义启停分享（默认走 admin API） */
  toggleShare?: (skillId: string, enabled: boolean) => Promise<SkillShareInfo>;
}

/**
 * 分身共享弹窗 — 生成/复制分享链接 + 二维码 + 共享开关
 *
 * 链接 = 当前站点 origin + /s/{shareCode}，二维码前端生成（qrcode 库），
 * 无需后端配置公网域名。
 */
export default function ShareModal({ skillId, ownerName, onClose, getOrCreate, toggleShare }: Props) {
  const doGetOrCreate = getOrCreate || adminGetOrCreateShare;
  const doToggle = toggleShare || adminToggleShare;
  const [share, setShare] = useState<SkillShareInfo | null>(null);
  const [qrDataUrl, setQrDataUrl] = useState('');
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);
  const [toggling, setToggling] = useState(false);

  const shareUrl = share ? `${window.location.origin}/s/${share.shareCode}` : '';

  useEffect(() => {
    doGetOrCreate(skillId)
      .then(setShare)
      .catch(e => setError(e?.message || '生成分享链接失败'));
  }, [skillId]);

  useEffect(() => {
    if (!shareUrl) return;
    QRCode.toDataURL(shareUrl, { width: 240, margin: 2 })
      .then(setQrDataUrl)
      .catch(() => setQrDataUrl(''));
  }, [shareUrl]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(shareUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      setError('复制失败，请手动选择链接复制');
    }
  };

  const toggle = async () => {
    if (!share || toggling) return;
    setToggling(true);
    try {
      const updated = await doToggle(skillId, !share.enabled);
      setShare(updated);
    } catch (e) {
      setError((e as Error)?.message || '操作失败');
    } finally {
      setToggling(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-navy/40 p-4" onClick={onClose}>
      <div className="w-full max-w-[400px] rounded-xl bg-bg p-6 shadow-xl" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between">
          <h3 className="text-h3 font-semibold text-foreground">共享「{ownerName}」的分身</h3>
          <button onClick={onClose} className="text-muted-foreground-2 hover:text-foreground" aria-label="关闭">✕</button>
        </div>
        <p className="mt-1 text-xs text-muted-foreground">用户微信内打开链接即可直接对话，无需安装任何应用</p>

        {error && <div className="mt-4 rounded-lg bg-danger-bg px-3 py-2 text-xs text-danger">{error}</div>}

        {share && (
          <>
            {/* 二维码 */}
            <div className="mt-5 flex justify-center">
              {qrDataUrl ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img src={qrDataUrl} alt="分享二维码" className={`h-[200px] w-[200px] rounded-lg border border-border ${share.enabled ? '' : 'opacity-30 grayscale'}`} />
              ) : (
                <div className="flex h-[200px] w-[200px] items-center justify-center rounded-lg border border-border text-xs text-muted-foreground-2">二维码生成中…</div>
              )}
            </div>

            {/* 链接 + 复制 */}
            <div className="mt-4 flex items-center gap-2">
              <input readOnly value={shareUrl}
                className="h-10 min-w-0 flex-1 rounded-lg border border-border bg-surface px-3 text-xs text-muted-foreground outline-none" />
              <button onClick={copy}
                className="h-10 flex-none rounded-lg bg-primary px-4 text-xs font-semibold text-white hover:bg-primary-hover">
                {copied ? '已复制 ✓' : '复制链接'}
              </button>
            </div>

            {/* 共享开关 */}
            <div className="mt-4 flex items-center justify-between rounded-lg bg-surface px-4 py-3">
              <div>
                <div className="text-body font-medium text-foreground">对外共享</div>
                <div className="mt-0.5 text-[11px] text-muted-foreground-2">
                  {share.enabled ? '所有拿到链接的用户/游客均可对话' : '已关闭，链接立即失效（可随时重新开启）'}
                </div>
              </div>
              <button onClick={toggle} disabled={toggling} aria-label="共享开关"
                className={`relative h-6 w-11 flex-none rounded-pill transition-colors ${share.enabled ? 'bg-primary' : 'bg-border-strong'}`}>
                <span className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow-card transition-all ${share.enabled ? 'left-[22px]' : 'left-0.5'}`} />
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
