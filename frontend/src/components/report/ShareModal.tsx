'use client';
import React from 'react';
import { QRCodeSVG } from 'qrcode.react';
import { copyToClipboard } from '@/lib/clipboard';

interface ShareModalProps {
  shareUrl: string;
  onClose: () => void;
}

export default function ShareModal({ shareUrl, onClose }: ShareModalProps) {
  const fullUrl = typeof window !== 'undefined' ? `${window.location.origin}${shareUrl}` : shareUrl;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm" onClick={onClose}>
      <div className="mx-4 w-full max-w-sm rounded-2xl bg-surface-2 p-6 shadow-xl" onClick={e => e.stopPropagation()}>
        <h3 className="mb-4 text-lg font-bold text-foreground text-center">分享萃取报告</h3>

        <div className="flex justify-center mb-4">
          <div className="rounded-xl bg-white p-3 shadow-sm">
            <QRCodeSVG value={fullUrl} size={160} />
          </div>
        </div>

        <div className="flex items-center gap-2 mb-4">
          <input
            type="text"
            readOnly
            value={fullUrl}
            className="flex-1 rounded-lg border border-border px-3 py-2 text-xs text-foreground bg-surface outline-none"
          />
          <button
            type="button"
            onClick={async () => {
              const ok = await copyToClipboard(fullUrl);
              alert(ok ? '链接已复制！' : '复制失败');
            }}
            className="rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-white shrink-0 transition-colors hover:bg-primary-hover"
          >
            复制链接
          </button>
        </div>

        <button
          type="button"
          onClick={onClose}
          className="w-full rounded-lg border border-border py-2 text-sm text-muted-foreground transition-colors hover:bg-surface"
        >
          关闭
        </button>
      </div>
    </div>
  );
}
