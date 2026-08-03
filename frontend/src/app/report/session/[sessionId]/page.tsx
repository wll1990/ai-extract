'use client';

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { API_BASE } from '@/lib/api/client';
import { copyToClipboard } from '@/lib/clipboard';

const GRAIN_ENOUGH = 10;
const GRAIN_SUGGEST_MORE = 5;

const PROGRESS_STEPS = [
  '分析对话内容...',
  '提取案例故事...',
  '梳理方法论步骤...',
  '挖掘决策点...',
  '提炼专家心法...',
  '生成最终报告...',
];

export default function ReportSessionPage() {
  const params = useParams();
  const router = useRouter();
  const sessionId = params.sessionId as string;

  const [html, setHtml] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pollGrains, setPollGrains] = useState(-1);
  const [activeStep, setActiveStep] = useState(0);
  const [pollCount, setPollCount] = useState(0);
  const [lastCheck, setLastCheck] = useState<Date | null>(null);
  const [pollStopped, setPollStopped] = useState(false);
  const [copied, setCopied] = useState(false);

  const timerRef = useRef<ReturnType<typeof setInterval>>();
  const startTimeRef = useRef(Date.now());

  // Progress animation
  useEffect(() => {
    if (html) return;
    const stepTimer = setInterval(() => {
      setActiveStep(prev => prev < PROGRESS_STEPS.length - 1 ? prev + 1 : prev);
    }, 2000);
    return () => clearInterval(stepTimer);
  }, [html]);

  // Poll report by sessionId
  const checkReport = useCallback(async () => {
    try {
      const r = await fetch(`${API_BASE}/reports/by-session/${encodeURIComponent(sessionId)}/html`, {
        credentials: 'include',
      });
      setLastCheck(new Date());
      setPollCount(c => c + 1);

      if (r.status === 200) {
        const text = await r.text();
        setHtml(text);
        if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = undefined; }
        return;
      }

      // 非200/202 — 停止轮询，显示错误
      if (r.status !== 202) {
        if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = undefined; }
        setPollStopped(true);
        let msg = '报告加载失败';
        try { const err = await r.json(); if (err.message) msg = err.message; } catch {}
        setError(msg);
        return;
      }

      // 202: parse grain info
      const body = await r.json();
      const grains: number = body.grains || 0;
      setPollGrains(grains);

      if (grains > 0 && grains < GRAIN_SUGGEST_MORE) {
        if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = undefined; }
        setPollStopped(true);
        return;
      }

      if (Date.now() - startTimeRef.current > 300_000) {
        if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = undefined; }
        setPollStopped(true);
      }
    } catch { /* retry next interval */ }
  }, [sessionId]);

  // 首次标记加载完成
  useEffect(() => {
    if (sessionId) setLoading(false);
  }, [sessionId]);

  // 启动轮询
  useEffect(() => {
    if (!sessionId || loading) return;

    checkReport();
    timerRef.current = setInterval(checkReport, 10_000);

    const timeoutId = setTimeout(() => {
      if (timerRef.current) { clearInterval(timerRef.current); timerRef.current = undefined; }
      setPollStopped(true);
    }, 300_000);

    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
      clearTimeout(timeoutId);
    };
  }, [sessionId, loading, checkReport]);

  const handleCopy = useCallback(async () => {
    const ok = await copyToClipboard('platform.mindforce.com');
    if (ok) {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  }, []);

  if (loading) {
    return (
      <div className="min-h-screen bg-surface flex items-center justify-center">
        <div className="w-8 h-8 animate-spin rounded-full border-2 border-primary border-t-transparent" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="min-h-screen bg-surface flex flex-col items-center justify-center gap-4">
        <span className="text-5xl">😕</span>
        <p className="text-sm text-muted-foreground">{error}</p>
        <button onClick={() => router.back()} className="text-sm text-primary hover:underline">返回</button>
      </div>
    );
  }

  // ── Report ready: render HTML ──
  if (html) {
    return (
      <div className="min-h-screen bg-surface">
        {/* Top bar */}
        <div className="sticky top-0 z-10 bg-white/90 backdrop-blur border-b border-border px-6 py-3 flex items-center justify-between">
          <button onClick={() => router.back()} className="text-sm text-muted-foreground hover:text-foreground">← 返回</button>
          <span className="text-xs text-muted-foreground">萃取报告</span>
          <div className="w-12" />
        </div>
        <div className="max-w-[900px] mx-auto px-6 py-8">
          <div className="bg-white rounded-2xl border border-border shadow-sm p-8" dangerouslySetInnerHTML={{ __html: html }} />
        </div>
      </div>
    );
  }

  // ── Polling: show progress ──
  return (
    <div className="min-h-screen bg-surface flex flex-col items-center justify-center px-6">
      <div className="max-w-md w-full text-center">
        {/* Spinner */}
        <div className="relative mx-auto mb-8 w-20 h-20">
          <div className="absolute inset-0 rounded-full border-4 border-amber-200" />
          <div className="absolute inset-0 rounded-full border-4 border-transparent border-t-amber-500 animate-spin" />
          <span className="absolute inset-0 flex items-center justify-center text-2xl">📄</span>
        </div>

        <h2 className="text-xl font-bold text-foreground mb-2">萃取报告生成中</h2>
        <p className="text-sm text-muted-foreground mb-6">
          AI 正在分析你的对话，提炼经验颗粒并生成报告
        </p>

        {/* Progress steps */}
        <div className="space-y-2 mb-6">
          {PROGRESS_STEPS.map((step, i) => (
            <div key={i} className={`flex items-center gap-3 text-sm transition-colors duration-500 ${
              i <= activeStep ? 'text-foreground' : 'text-muted-foreground-2'
            }`}>
              <span className={`w-5 h-5 rounded-full flex items-center justify-center text-xs transition-colors ${
                i < activeStep ? 'bg-green-100 text-green-600' :
                i === activeStep ? 'bg-amber-100 text-amber-600 animate-pulse' :
                'bg-gray-100 text-gray-400'
              }`}>
                {i < activeStep ? '✓' : i + 1}
              </span>
              {step}
            </div>
          ))}
        </div>

        {/* Shimmer bar */}
        <div className="h-1 w-full rounded-full bg-gray-100 overflow-hidden mb-4">
          <div className="h-full w-1/2 rounded-full bg-gradient-to-r from-amber-400 via-orange-400 to-amber-400 animate-[marquee_1.8s_linear_infinite]" />
        </div>

        <p className="text-xs text-muted-foreground mb-8">
          {lastCheck
            ? `上次检查：${lastCheck.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}（第 ${pollCount} 次）`
            : '准备中...'}
        </p>

        {/* Poll stopped states */}
        {pollStopped && pollGrains > 0 && pollGrains < GRAIN_SUGGEST_MORE && (
          <div className="rounded-xl bg-amber-50 border border-amber-200 p-4 mb-4">
            <p className="text-sm font-medium text-amber-700">内容还不够丰富</p>
            <p className="text-xs text-amber-600 mt-1">
              当前已采集 {pollGrains} 条经验颗粒，建议返回继续补充更多案例细节
            </p>
            <button onClick={() => router.back()}
              className="mt-3 text-sm font-medium text-amber-700 hover:text-amber-800 underline">
              ← 返回继续补充
            </button>
          </div>
        )}

        {pollStopped && pollGrains >= GRAIN_SUGGEST_MORE && (
          <div className="rounded-xl bg-blue-50 border border-blue-200 p-4 mb-4">
            <p className="text-sm font-medium text-blue-700">报告生成超时</p>
            <p className="text-xs text-blue-600 mt-1">
              已等待 5 分钟，报告可能仍在生成中。请稍后刷新页面查看。
            </p>
            <button onClick={() => { setPollStopped(false); startTimeRef.current = Date.now(); checkReport(); timerRef.current = setInterval(checkReport, 10_000); }}
              className="mt-3 text-sm font-medium text-blue-700 hover:text-blue-800 underline">
              🔄 继续等待
            </button>
          </div>
        )}

        {/* Platform link */}
        <div className="rounded-xl bg-surface-2 border border-border p-4 text-left">
          <p className="text-xs font-medium text-foreground mb-2">💡 查看完整报告与审核进度</p>
          <p className="text-xs text-muted-foreground mb-3">
            请使用 PC 浏览器访问以下地址，登录后查看完整萃取报告和审核进度。
          </p>
          <div className="flex items-center gap-2 rounded-lg bg-white border border-border px-3 py-2">
            <span className="text-sm font-medium text-primary select-all flex-1">platform.mindforce.com</span>
            <button onClick={handleCopy}
              className="flex-shrink-0 rounded-md bg-primary px-3 py-1 text-xs text-white font-medium hover:bg-primary-hover">
              {copied ? '已复制' : '复制'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
