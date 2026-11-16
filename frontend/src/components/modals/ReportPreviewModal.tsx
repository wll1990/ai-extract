'use client';

import React, { useEffect, useState, useCallback, useRef } from 'react';
import { apiClient, API_BASE, authHeaders} from '@/lib/api/client';
import { getToken } from '@/lib/storage';

interface Props {
  skillId: string;
  skillName: string;
  onClose: () => void;
}

interface Readiness {
  ready: boolean;
  grains: number;
  scenes: number;
  needGrains: number;
  needScenes: number;
}

export function ReportPreviewModal({ skillId, skillName, onClose }: Props) {
  const [loading, setLoading] = useState(true);
  const [readiness, setReadiness] = useState<Readiness | null>(null);
  const [readinessError, setReadinessError] = useState('');
  const [htmlContent, setHtmlContent] = useState<string | null>(null);
  const [htmlError, setHtmlError] = useState('');
  const iframeRef = useRef<HTMLIFrameElement>(null);

  useEffect(() => {
    if (!readiness?.ready) return;
    setHtmlError('');
    fetch(`${API_BASE}/admin/skills/${skillId}/report`, {
      headers: { ...authHeaders() },
    })
      .then(async r => {
        if (!r.ok) throw new Error(`报告加载失败 (${r.status})`);
        return r.text();
      })
      .then(text => {
        if (!text.trim()) throw new Error('报告内容为空，请重新生成');
        setHtmlContent(text);
      })
      .catch((e: unknown) => setHtmlError(e instanceof Error ? e.message : '报告加载失败'));
  }, [readiness?.ready, skillId]);

  useEffect(() => {
    apiClient(`/admin/skills/${skillId}/report-readiness`)
      .then((d: any) => { setReadiness(d || null); setReadinessError(''); })
      .catch((e: unknown) => {
        setReadiness(null);
        setReadinessError(e instanceof Error ? `报告就绪状态加载失败：${e.message}` : '报告就绪状态加载失败');
      })
      .finally(() => setLoading(false));
  }, [skillId]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const downloadHtml = async () => {
    try {
      const res = await fetch(`${API_BASE}/reports/by-skill/${skillId}/html`, {
        headers: { ...authHeaders() },
      });
      if (!res.ok) throw new Error(`下载失败 (${res.status})`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `萃取报告-${skillName}.html`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setHtmlError(e instanceof Error ? e.message : '下载失败');
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-black/60 backdrop-blur-sm" onClick={onClose}>
      {/* 顶栏 */}
      <div className="flex items-center justify-between px-6 py-3 bg-white/95 backdrop-blur border-b border-gray-200 flex-shrink-0" onClick={e => e.stopPropagation()}>
        <div className="flex items-center gap-3">
          <span className="text-sm font-bold text-gray-800">{skillName} · 萃取报告</span>
          {!loading && readiness && (
            <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${
              readiness.ready ? 'bg-green-100 text-green-700' : 'bg-amber-100 text-amber-700'
            }`}>
              {readiness.ready ? '✓ 已就绪' : `颗粒 ${readiness.grains}/10 · 场景 ${readiness.scenes}/3`}
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          {readiness?.ready && (
            <>
              <button onClick={() => iframeRef.current?.contentWindow?.print()}
                className="px-3 py-1.5 text-xs font-medium bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200 transition-colors">
                导出 PDF
              </button>
              <button onClick={downloadHtml}
                className="px-3 py-1.5 text-xs font-medium bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200 transition-colors">
                下载 HTML
              </button>
            </>
          )}
          <button onClick={onClose}
            className="ml-2 w-8 h-8 flex items-center justify-center rounded-lg hover:bg-gray-100 text-gray-500 transition-colors">
            ✕
          </button>
        </div>
      </div>

      {/* 内容区 */}
      <div className="flex-1 overflow-hidden" onClick={e => e.stopPropagation()}>
        {loading ? (
          <div className="flex items-center justify-center h-full">
            <div className="text-center">
              <div className="w-12 h-12 border-3 border-indigo-200 border-t-indigo-500 rounded-full animate-spin mx-auto mb-4" />
              <p className="text-white/80 text-sm">正在加载报告...</p>
            </div>
          </div>
        ) : readinessError ? (
          <div className="flex h-full items-center justify-center">
            <div className="rounded-2xl bg-white p-8 text-center shadow-2xl">
              <p className="text-sm text-red-500">{readinessError}</p>
              <button onClick={onClose} className="mt-4 rounded-lg border px-4 py-2 text-sm">关闭</button>
            </div>
          </div>
        ) : readiness && !readiness.ready ? (
          <div className="flex items-center justify-center h-full">
            <div className="bg-white rounded-2xl p-10 max-w-md text-center shadow-2xl">
              <div className="text-5xl mb-4">📋</div>
              <h3 className="text-lg font-bold text-gray-800 mb-2">报告尚未就绪</h3>
              <p className="text-sm text-gray-500 mb-6">至少需要 10 条活跃颗粒和 3 个场景覆盖才能生成报告</p>
              <div className="flex gap-3 justify-center mb-6">
                <div className="bg-gray-50 rounded-xl px-4 py-3 text-center">
                  <div className={`text-2xl font-bold ${readiness.grains >= 10 ? 'text-green-500' : 'text-amber-500'}`}>{readiness.grains}</div>
                  <div className="text-xs text-gray-400 mt-1">活跃颗粒 / 10</div>
                </div>
                <div className="bg-gray-50 rounded-xl px-4 py-3 text-center">
                  <div className={`text-2xl font-bold ${readiness.scenes >= 3 ? 'text-green-500' : 'text-amber-500'}`}>{readiness.scenes}</div>
                  <div className="text-xs text-gray-400 mt-1">场景覆盖 / 3</div>
                </div>
              </div>
              <div className="text-xs text-gray-400 space-y-1">
                {readiness.needGrains > 0 && <p>还差 {readiness.needGrains} 条颗粒 — 建议继续上传素材或手动补充</p>}
                {readiness.needScenes > 0 && <p>还差 {readiness.needScenes} 个场景 — 确保颗粒覆盖不同业务场景</p>}
              </div>
              <button onClick={onClose}
                className="mt-6 px-6 py-2 bg-indigo-500 text-white text-sm font-medium rounded-lg hover:bg-indigo-600 transition-colors">
                知道了
              </button>
            </div>
          </div>
        ) : htmlError ? (
          <div className="flex h-full items-center justify-center">
            <div className="rounded-2xl bg-white p-8 text-center shadow-2xl">
              <p className="text-sm text-red-500">{htmlError}</p>
              <button onClick={onClose} className="mt-4 rounded-lg border px-4 py-2 text-sm">关闭</button>
            </div>
          </div>
        ) : (
          <iframe ref={iframeRef} srcDoc={htmlContent || ''} className="w-full h-full border-0 bg-white" title="萃取报告" />
        )}
      </div>
    </div>
  );
}
