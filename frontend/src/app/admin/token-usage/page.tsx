'use client';

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { getTokenSummary, getTokenDaily, getTokenLogs, type TokenSummary, type DailyTokenRow, type TokenLogItem } from '@/lib/api/admin';

function fmt(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
  return String(n);
}

export default function TokenUsagePage() {
  const [summary, setSummary] = useState<TokenSummary | null>(null);
  const [daily, setDaily] = useState<DailyTokenRow[]>([]);
  const [logs, setLogs] = useState<TokenLogItem[]>([]);
  const [page, setPage] = useState(0);
  const [totalLogs, setTotalLogs] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [logsError, setLogsError] = useState('');
  const [days, setDays] = useState(7);
  const pageRef = useRef(0);
  const size = 20;

  const loadSummary = useCallback(() => {
    getTokenSummary().then(setSummary).catch(e => setError(e?.message || '加载汇总失败'));
  }, []);

  const loadDaily = useCallback((d: number) => {
    getTokenDaily(d).then(setDaily).catch(() => {});
  }, []);

  const loadLogs = useCallback((p: number) => {
    setLogsError('');
    getTokenLogs(p, size)
      .then(d => {
        setLogs(d.items || []);
        setTotalLogs((d as any).total || 0);
      })
      .catch(e => setLogsError(e?.message || '加载明细失败'));
  }, []);

  useEffect(() => {
    Promise.all([loadSummary(), loadDaily(days), loadLogs(0)]).finally(() => setLoading(false));
  }, [loadSummary, loadDaily, loadLogs, days]);

  const goPage = (delta: number) => {
    const next = pageRef.current + delta;
    if (next < 0) return;
    pageRef.current = next;
    setPage(next);
    loadLogs(next);
  };

  const totalPages = Math.max(1, Math.ceil(totalLogs / size));
  const hasMore = page < totalPages - 1;

  if (loading) {
    return (
      <div className="min-h-screen bg-surface px-6 py-8">
        <div className="mx-auto max-w-[960px] text-sm text-muted-foreground">加载中...</div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <h1 className="text-2xl font-bold text-foreground mb-6">Token 用量统计</h1>

        {error && (
          <div className="mb-6 rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-sm text-red-600">{error}</div>
        )}

        {/* 汇总卡片 */}
        {summary && (
          <div className="grid grid-cols-3 gap-4 mb-8">
            <SummaryCard label="今日输入" value={summary.today.inputTokens} sub={`${summary.today.count} 次调用`} />
            <SummaryCard label="今日输出" value={summary.today.outputTokens} sub={`${summary.today.count} 次调用`} />
            <SummaryCard label="本月合计" value={summary.month.inputTokens + summary.month.outputTokens}
              sub={`入 ${fmt(summary.month.inputTokens)} / 出 ${fmt(summary.month.outputTokens)}`} />
            <SummaryCard label="累计输入" value={summary.total.inputTokens} sub="平台上线至今" />
            <SummaryCard label="累计输出" value={summary.total.outputTokens} sub="平台上线至今" />
            <SummaryCard label="本月调用" value={summary.month.count} sub="次" />
          </div>
        )}

        {/* 按天趋势 */}
        <div className="bg-white rounded-xl border border-border overflow-hidden mb-8">
          <div className="px-5 py-4 border-b border-border flex items-center justify-between">
            <h2 className="font-semibold text-foreground">📊 近 {days} 天趋势</h2>
            <div className="flex gap-1">
              {[7, 30].map(d => (
                <button key={d} onClick={() => { setDays(d); loadDaily(d); }}
                  className={`text-xs px-3 py-1 rounded-full ${days === d ? 'bg-primary text-white' : 'bg-surface-2 text-muted-foreground'}`}>
                  {d}天
                </button>
              ))}
            </div>
          </div>
          {/* 柱状图 */}
          {daily.length > 0 && (
            <div className="px-5 py-4">
              <TokenBarChart data={daily} />
            </div>
          )}
          {/* 明细表 */}
          <div className="overflow-x-auto border-t border-border">
            <table className="w-full text-sm">
              <thead className="bg-surface-2 text-muted-foreground text-xs uppercase">
                <tr>
                  <th className="text-left px-5 py-3">日期</th>
                  <th className="text-right px-5 py-3">输入 Token</th>
                  <th className="text-right px-5 py-3">输出 Token</th>
                  <th className="text-right px-5 py-3">调用次数</th>
                </tr>
              </thead>
              <tbody>
                {daily.map((d, i) => (
                  <tr key={d.date} className={`border-t border-border ${i % 2 === 0 ? 'bg-white' : 'bg-surface-2/30'}`}>
                    <td className="px-5 py-2.5 font-medium">{d.date}</td>
                    <td className="px-5 py-2.5 text-right tabular-nums">{fmt(d.inputTokens)}</td>
                    <td className="px-5 py-2.5 text-right tabular-nums">{fmt(d.outputTokens)}</td>
                    <td className="px-5 py-2.5 text-right tabular-nums">{d.count}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        {/* 最近明细 */}
        <div className="bg-white rounded-xl border border-border overflow-hidden">
          <div className="px-5 py-4 border-b border-border flex items-center justify-between">
            <h2 className="font-semibold text-foreground">📋 最近调用明细</h2>
            <div className="flex items-center gap-2">
              <button onClick={() => goPage(-1)}
                disabled={page === 0}
                className="text-xs px-3 py-1.5 rounded-lg border border-border hover:bg-surface-2 disabled:opacity-30">
                上一页
              </button>
              <span className="text-xs text-muted-foreground">
                第 {page + 1}/{totalPages} 页（共 {totalLogs} 条）
              </span>
              <button onClick={() => goPage(1)}
                disabled={!hasMore}
                className="text-xs px-3 py-1.5 rounded-lg border border-border hover:bg-surface-2 disabled:opacity-30">
                下一页
              </button>
            </div>
          </div>
          {logsError && (
            <div className="px-5 py-3 bg-red-50 border-b border-red-200 text-xs text-red-600">{logsError}</div>
          )}
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-surface-2 text-muted-foreground text-xs uppercase">
                <tr>
                  <th className="text-left px-5 py-3">时间</th>
                  <th className="text-left px-5 py-3">用户</th>
                  <th className="text-left px-5 py-3">模型</th>
                  <th className="text-right px-5 py-3">输入</th>
                  <th className="text-right px-5 py-3">输出</th>
                  <th className="text-right px-5 py-3">Prompt 字符</th>
                  <th className="text-right px-5 py-3">响应字符</th>
                </tr>
              </thead>
              <tbody>
                {logs.length === 0 ? (
                  <tr><td colSpan={7} className="px-5 py-8 text-center text-muted-foreground">暂无数据</td></tr>
                ) : logs.map((l, i) => (
                  <tr key={l.id} className={`border-t border-border text-xs ${i % 2 === 0 ? 'bg-white' : 'bg-surface-2/30'}`}>
                    <td className="px-5 py-2.5 text-muted-foreground whitespace-nowrap">
                      {l.createdAt ? l.createdAt.substring(11, 19) : '-'}
                    </td>
                    <td className="px-5 py-2.5 font-mono text-muted-foreground">
                      {l.userId ? l.userId.substring(0, 8) : '-'}
                    </td>
                    <td className="px-5 py-2.5">
                      <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-medium ${
                        l.modelType === 'CHAT' ? 'bg-blue-100 text-blue-700' : 'bg-amber-100 text-amber-700'
                      }`}>
                        {l.modelName || l.modelType}
                      </span>
                    </td>
                    <td className="px-5 py-2.5 text-right tabular-nums">{fmt(l.inputTokens)}</td>
                    <td className="px-5 py-2.5 text-right tabular-nums">{fmt(l.outputTokens)}</td>
                    <td className="px-5 py-2.5 text-right tabular-nums text-muted-foreground">{fmt(l.promptChars)}</td>
                    <td className="px-5 py-2.5 text-right tabular-nums text-muted-foreground">{fmt(l.completionChars)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
}

function SummaryCard({ label, value, sub }: { label: string; value: number; sub: string }) {
  return (
    <div className="rounded-xl bg-white border border-border p-5">
      <p className="text-xs text-muted-foreground mb-1">{label}</p>
      <p className="text-2xl font-bold text-foreground tabular-nums">{fmt(value)}</p>
      <p className="text-[10px] text-muted-foreground-2 mt-0.5">{sub}</p>
    </div>
  );
}

/** 简易 SVG 柱状图 — 展示每日输入+输出 token */
function TokenBarChart({ data }: { data: DailyTokenRow[] }) {
  const maxVal = Math.max(...data.map(d => d.inputTokens + d.outputTokens), 1);
  const h = 140;
  const w = Math.max(data.length * 44, 300);
  const barW = 16;
  const gap = 6;

  return (
    <div className="overflow-x-auto">
      <svg width={w} height={h} className="block">
        {/* 网格线 */}
        {[0, 0.5, 1].map(pct => (
          <line key={pct} x1={0} y1={h - pct * (h - 20) - 10} x2={w} y2={h - pct * (h - 20) - 10}
            stroke="#e5e7eb" strokeDasharray={pct === 0 ? 'none' : '3,3'} />
        ))}
        {/* 柱子 */}
        {data.map((d, i) => {
          const x = i * (barW * 2 + gap * 2) + 8;
          const inH = (d.inputTokens / maxVal) * (h - 30);
          const outH = (d.outputTokens / maxVal) * (h - 30);
          const totalH = inH + outH;
          return (
            <g key={d.date}>
              {/* 输入 token（蓝色） */}
              <rect x={x} y={h - totalH - 10} width={barW} height={Math.max(inH, 1)}
                fill="#2147ff" rx={2} />
              {/* 输出 token（红色） */}
              <rect x={x + barW + gap} y={h - outH - 10} width={barW} height={Math.max(outH, 1)}
                fill="#ff4d5f" rx={2} />
              {/* 日期标签 */}
              <text x={x + barW + gap / 2} y={h} textAnchor="middle"
                fill="#747f9e" fontSize={10}>
                {d.date.slice(5)}</text>
            </g>
          );
        })}
      </svg>
      {/* 图例 */}
      <div className="flex items-center gap-4 justify-center mt-1 pb-2">
        <span className="flex items-center gap-1 text-xs text-muted-foreground">
          <span className="w-3 h-3 rounded-sm bg-[#2147ff] inline-block" /> 输入
        </span>
        <span className="flex items-center gap-1 text-xs text-muted-foreground">
          <span className="w-3 h-3 rounded-sm bg-[#ff4d5f] inline-block" /> 输出
        </span>
      </div>
    </div>
  );
}
