'use client';
import React, { useState } from 'react';
import ShareModal from './ShareModal';
import { shareReport, regenerateReport } from '@/lib/api/report';

interface ReportViewerProps {
  htmlUrl: string;
  downloadUrl: string;
  reportId?: string;        // 内网页面有值，公开页为 null
  shareCode?: string;       // 已有分享码
  shareUrl?: string;        // 已有分享链接
  canRegenerate?: boolean;  // 管理员可重新生成
}

export default function ReportViewer({
  htmlUrl,
  downloadUrl,
  reportId,
  shareCode: initialShareCode,
  shareUrl: initialShareUrl,
  canRegenerate,
}: ReportViewerProps) {
  const [showShare, setShowShare] = useState(false);
  const [shareInfo, setShareInfo] = useState<{ shareCode: string; shareUrl: string } | null>(
    initialShareCode ? { shareCode: initialShareCode, shareUrl: initialShareUrl || `/public/report/${initialShareCode}` } : null
  );
  const [regenerating, setRegenerating] = useState(false);

  const handleShare = async () => {
    if (shareInfo) {
      setShowShare(true);
      return;
    }
    if (!reportId) return;
    try {
      const result = await shareReport(reportId);
      setShareInfo(result);
      setShowShare(true);
    } catch (e: any) {
      alert(e?.message || '分享失败');
    }
  };

  const handleDownload = () => {
    const a = document.createElement('a');
    a.href = downloadUrl;
    a.download = '';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  };

  const handleRegenerate = async () => {
    if (!reportId) return;
    setRegenerating(true);
    try {
      await regenerateReport(reportId);
      alert('报告已重新生成，分享链接已刷新');
      // 刷新 iframe（加时间戳绕过缓存）
      const iframe = document.querySelector('iframe[data-report]') as HTMLIFrameElement;
      if (iframe) iframe.src = htmlUrl + '?t=' + Date.now();
    } catch (e: any) {
      alert(e?.message || '重新生成失败');
    } finally {
      setRegenerating(false);
    }
  };

  return (
    <div className="min-h-screen bg-surface">
      {/* TopBar */}
      <div className="sticky top-0 z-40 flex items-center justify-end gap-3 border-b border-border bg-white/90 backdrop-blur px-6 py-3">
        <button
          type="button"
          onClick={handleShare}
          className="rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-primary-hover"
        >
          📋 分享
        </button>
        <button
          type="button"
          onClick={handleDownload}
          className="rounded-lg border border-border px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-primary-light"
        >
          📥 下载
        </button>
        {canRegenerate && (
          <button
            type="button"
            onClick={handleRegenerate}
            disabled={regenerating}
            className="rounded-lg border border-border px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-primary-light disabled:opacity-50"
          >
            {regenerating ? '⏳ 生成中...' : '🔄 重新生成'}
          </button>
        )}
      </div>

      {/* iframe */}
      <iframe
        data-report
        src={htmlUrl}
        style={{ width: '100%', height: 'calc(100vh - 52px)', border: 'none' }}
        title="萃取报告"
      />

      {/* Share Modal */}
      {showShare && shareInfo && (
        <ShareModal
          shareUrl={shareInfo.shareUrl}
          onClose={() => setShowShare(false)}
        />
      )}
    </div>
  );
}
