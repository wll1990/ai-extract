'use client';

import { useEffect, useState, useCallback, useRef } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { apiClient } from '@/lib/api/client';
import { getToken } from '@/lib/storage';
import { copyToClipboard } from '@/lib/clipboard';

/** 颗粒分级阈值，与 application.yml app.interview.* 保持一致 */
const GRAIN_ENOUGH = 10;
const GRAIN_SUGGEST_MORE = 5;

const COLLECT_LABELS: Record<string, string> = {
  caseStory: '案例故事', steps: '核心步骤', decision: '关键决策',
  mindset: '专家心法', boundary: '适用边界', checklist: '行动清单',
};

interface SessionData {
  sessionId: string;
  topic: string;
  status: string;
  collectStatus?: Record<string, string>;
}

export default function H5ReportPage() {
  const params = useParams();
  const sessionId = params.sessionId as string;
  const router = useRouter();

  const [session, setSession] = useState<SessionData | null>(null);
  const [html, setHtml] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastCheck, setLastCheck] = useState<Date | null>(null);
  const [pollCount, setPollCount] = useState(0);
  const [copied, setCopied] = useState(false);
  const [pollStopped, setPollStopped] = useState(false);
  const [pollGrains, setPollGrains] = useState(-1); // -1 = 尚未轮询，0 = 后端返回 0 颗粒
  const [pollNeedGrains, setPollNeedGrains] = useState(0);
  const timerRef = useRef<ReturnType<typeof setInterval>>();
  const startTimeRef = useRef(Date.now());

  const authHeaders = useCallback((): Record<string, string> => {
    const token = getToken();
    return token ? { Authorization: `Bearer ${token}` } : {};
  }, []);

  // Load session data (always available)
  useEffect(() => {
    if (!sessionId) return;
    apiClient(`/interviews/${encodeURIComponent(sessionId)}`)
      .then(r => r.json())
      .then(d => {
        if (d.code === 200) setSession(d.data);
        else setError('会话不存在');
      })
      .catch(() => setError('加载失败'))
      .finally(() => setLoading(false));
  }, [sessionId]); // eslint-disable-line react-hooks/exhaustive-deps

  // Poll for report HTML
  const checkReport = useCallback(async () => {
    try {
      const r = await fetch(`/api/v1/reports/by-session/${encodeURIComponent(sessionId)}/html`, {
        headers: { Authorization: `Bearer ${getToken()}` },
      });
      setLastCheck(new Date());
      setPollCount(c => c + 1);

      if (r.status === 200) {
        const text = await r.text();
        setHtml(text);
        if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = undefined; }
        return;
      }

      // 202: 解析 grain 信息，分级处理
      const body = await r.json();
      const grains: number = body.grains || 0;
      const needGrains: number = body.needGrains || 0;
      setPollGrains(grains);
      setPollNeedGrains(needGrains);

      // >0 且 <5 条：提取已完成但颗粒不足，停止轮询
      if (grains > 0 && grains < GRAIN_SUGGEST_MORE) {
        if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = undefined; }
        setPollStopped(true);
        return;
      }

      // 5 分钟超时
      if (Date.now() - startTimeRef.current > 300_000) {
        if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = undefined; }
        setPollStopped(true);
      }
    } catch { /* network error, retry next interval */ }
  }, [sessionId]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!sessionId || loading) return;
    checkReport(); // immediate first check
    timerRef.current = setInterval(checkReport, 10_000);
    // 独立超时兜底：不依赖 response 到达，5 分钟后强制停止轮询
    const timeoutId = setTimeout(() => {
      if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = undefined; }
      setPollStopped(true);
    }, 300_000);
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
      clearTimeout(timeoutId);
    };
  }, [sessionId, loading, checkReport]);

  const platformHost = typeof window !== 'undefined' ? window.location.host : '';

  const handleCopy = useCallback(async () => {
    const ok = await copyToClipboard(platformHost);
    if (ok) {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  }, [platformHost]);

  const lastCheckLabel = lastCheck
    ? `上次检查：${lastCheck.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}`
    : '';

  const collectModuleList = session?.collectStatus
    ? Object.entries(COLLECT_LABELS).map(([key, label]) => ({
        key, label,
        done: session.collectStatus?.[key] === 'collected',
      }))
    : [];

  const doneCount = collectModuleList.filter(m => m.done).length;

  if (loading) return (
    <div className="min-h-screen bg-[#f7f9ff] flex items-center justify-center">
      <p className="text-sm text-[#747f9e]">加载中...</p>
    </div>
  );

  if (error) return (
    <div className="min-h-screen bg-[#f7f9ff] flex flex-col items-center justify-center px-6 text-center">
      <span className="text-4xl mb-3">📄</span>
      <h2 className="text-lg font-semibold text-[#10162f] mb-2">{error}</h2>
      <button onClick={() => router.back()} className="mt-4 text-sm text-[#2147ff] font-medium">返回</button>
    </div>
  );

  // HTML report ready — render it
  if (html) return (
    <div className="min-h-screen bg-[#f7f9ff]" style={{ background: 'radial-gradient(circle at 50% 0%, #eef2ff 0%, #f7f9ff 60%)' }}>
      <div className="mx-auto max-w-[640px] px-5 py-10">
        <button onClick={() => router.back()} className="text-sm text-[#747f9e] hover:text-[#10162f] mb-6">← 返回</button>
        <div className="bg-white rounded-[26px] border border-[#e1e7ff] p-5 shadow-[0_18px_50px_rgba(42,74,177,0.08)] report-html-content"
          dangerouslySetInnerHTML={{ __html: html }} />
        {/* platform link */}
        {platformHost && (
        <div className="mt-6 bg-white rounded-[20px] border border-[#e1e7ff] px-5 py-4 text-center shadow-[0_18px_50px_rgba(42,74,177,0.04)]">
          <p className="text-xs text-[#747f9e] mb-2">💡 查看完整报告与审核进度</p>
          <div className="flex items-center justify-center gap-2">
            <span className="text-sm font-medium text-[#2147ff] select-all">{platformHost}</span>
            <button onClick={handleCopy}
              className="rounded-lg bg-[#2147ff] px-3 py-1.5 text-xs text-white font-medium hover:bg-[#1a38cc] transition-colors">
              {copied ? '已复制' : '复制'}
            </button>
          </div>
        </div>
        )}
      </div>
    </div>
  );

  // Waiting state — show session grain summary + polling animation
  return (
    <div className="min-h-screen bg-[#f7f9ff]" style={{ background: 'radial-gradient(circle at 50% 0%, #eef2ff 0%, #f7f9ff 60%)' }}>
      <div className="mx-auto max-w-[640px] px-5 py-10">
        <button onClick={() => router.back()} className="text-sm text-[#747f9e] hover:text-[#10162f] mb-6">← 返回</button>

        {/* Session info */}
        <div className="bg-white rounded-[26px] border border-[#e1e7ff] p-6 mb-6 shadow-[0_18px_50px_rgba(42,74,177,0.08)]">
          <h1 className="text-[20px] font-bold text-[#10162f] mb-1">{session?.topic || '萃取报告'}</h1>
          <p className="text-xs text-[#747f9e]">AI 经验萃取师</p>
        </div>

        {/* Grain collection summary */}
        {collectModuleList.length > 0 && (
          <div className="bg-white rounded-[20px] border border-[#e1e7ff] p-5 mb-6">
            <h2 className="text-sm font-semibold text-[#10162f] mb-3">
              本次萃取成果 <span className="text-[#2147ff]">{doneCount}/{collectModuleList.length} 模块已采集</span>
            </h2>
            <div className="grid grid-cols-2 gap-2">
              {collectModuleList.map(m => (
                <div key={m.key} className={`flex items-center gap-2 rounded-lg px-3 py-2 text-sm ${
                  m.done ? 'bg-[#f0fdf4] text-[#166534]' : 'bg-[#f8fafc] text-[#94a3b8]'
                }`}>
                  <span>{m.done ? '✅' : '⬜'}</span>
                  <span>{m.label}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Waiting for report — grain-based messaging */}
        {!pollStopped && (
          <div className="bg-white rounded-[20px] border border-[#e1e7ff] p-6 mb-6 text-center">
            <div className="mb-4">
              <div className="mx-auto w-12 h-12 rounded-full bg-[#eef2ff] flex items-center justify-center mb-3 animate-pulse">
                <span className="text-2xl">⏳</span>
              </div>
              <h2 className="text-base font-semibold text-[#10162f] mb-1">完整报告生成中</h2>
              {pollGrains > 0 ? (
                <p className="text-xs text-[#747f9e]">
                  已生成 {pollGrains} 条颗粒，距报告标准还差 {pollNeedGrains} 条。继续等待...
                </p>
              ) : (
                <p className="text-xs text-[#747f9e]">AI 正在分析你的访谈内容，预计 2-3 分钟完成</p>
              )}
            </div>
            <div className="mx-auto w-48 h-1.5 rounded-full overflow-hidden bg-[#edf0fb] mb-3">
              <div className="h-full w-3/4 rounded-full"
                style={{ animation: 'shimmer 2s linear infinite', background: 'linear-gradient(90deg, #eef2ff 25%, #2147ff 50%, #eef2ff 75%)', backgroundSize: '200% 100%' }} />
            </div>
            <p className="text-xs text-[#747f9e] mb-1">页面每 10 秒自动检查，请稍候</p>
            {lastCheck && (
              <p className="text-[10px] text-[#a0aec0] mb-3">· {lastCheckLabel} · 已检查 {pollCount} 次</p>
            )}
            <button onClick={checkReport}
              className="text-xs text-[#2147ff] font-medium hover:underline">手动刷新</button>
          </div>
        )}

        {/* Poll stopped — >0 and <5 grains: extraction ran but insufficient */}
        {pollStopped && pollGrains > 0 && pollGrains < GRAIN_SUGGEST_MORE && (
          <div className="bg-white rounded-[20px] border border-[#e1e7ff] p-6 mb-6 text-center">
            <div className="mb-4">
              <span className="text-4xl">⚠️</span>
              <h2 className="text-base font-semibold text-[#10162f] mt-3 mb-1">内容还不够丰富</h2>
              <p className="text-xs text-[#747f9e]">本次仅生成 {pollGrains} 条颗粒，暂未达到报告标准（需 {GRAIN_ENOUGH} 条）。建议继续补充更多案例和细节。</p>
            </div>
          </div>
        )}

        {pollStopped && pollGrains >= GRAIN_SUGGEST_MORE && (
          <div className="bg-white rounded-[20px] border border-[#e1e7ff] p-6 mb-6 text-center">
            <div className="mb-4">
              <span className="text-4xl">⏰</span>
              <h2 className="text-base font-semibold text-[#10162f] mt-3 mb-1">检查超时</h2>
              <p className="text-xs text-[#747f9e]">已等待超过 5 分钟，报告仍未就绪（已生成 {pollGrains} 条颗粒，还差 {pollNeedGrains} 条）。建议返回继续补充更多内容。</p>
            </div>
          </div>
        )}

        {/* platform link */}
        {platformHost && (
        <div className="bg-white rounded-[20px] border border-[#e1e7ff] px-5 py-4 text-center shadow-[0_18px_50px_rgba(42,74,177,0.04)]">
          <p className="text-xs text-[#747f9e] mb-2">💡 查看完整报告与审核进度</p>
          <div className="flex items-center justify-center gap-2">
            <span className="text-sm font-medium text-[#2147ff] select-all">{platformHost}</span>
            <button onClick={handleCopy}
              className="rounded-lg bg-[#2147ff] px-3 py-1.5 text-xs text-white font-medium hover:bg-[#1a38cc] transition-colors">
              {copied ? '已复制' : '复制'}
            </button>
          </div>
        </div>
        )}
      </div>

      {/* shimmer keyframe */}
      <style dangerouslySetInnerHTML={{ __html: `
        @keyframes shimmer {
          0% { background-position: 200% 0; }
          100% { background-position: -200% 0; }
        }
      `}} />
    </div>
  );
}
