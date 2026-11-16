'use client';

import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  getTokenSummary, getTokenDaily, getTokenLogs, getTokenByModel, getTokenByCompany,
  type TokenSummary, type DailyTokenRow, type TokenLogItem, type ModelTokenRow, type CompanyTokenRow,
} from '@/lib/api/admin';

function fmt(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
  return String(n);
}

function exact(n: number): string {
  return n.toLocaleString('zh-CN');
}

/** 按 N 天生成连续日期，缺失日期补 0（x 轴不跳日） */
function fillDaily(rows: DailyTokenRow[], days: number): DailyTokenRow[] {
  const byDate = new Map(rows.map(r => [r.date, r]));
  const out: DailyTokenRow[] = [];
  const today = new Date();
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(today);
    d.setDate(today.getDate() - i);
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    out.push(byDate.get(key) || { date: key, inputTokens: 0, outputTokens: 0, count: 0 });
  }
  return out;
}

export default function TokenUsagePage() {
  const [summary, setSummary] = useState<TokenSummary | null>(null);
  const [daily, setDaily] = useState<DailyTokenRow[]>([]);
  const [modelRows, setModelRows] = useState<ModelTokenRow[]>([]);
  const [companyRows, setCompanyRows] = useState<CompanyTokenRow[]>([]);
  const [logs, setLogs] = useState<TokenLogItem[]>([]);
  const [page, setPage] = useState(0);
  const [totalLogs, setTotalLogs] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [dailyLoading, setDailyLoading] = useState(false);
  const [dailyError, setDailyError] = useState('');
  const [logsError, setLogsError] = useState('');
  const [days, setDays] = useState(7);
  const size = 20;

  // 快速切换 7/30 天时丢弃过期响应
  const dailyReq = useRef(0);
  const logsReq = useRef(0);

  const loadSummary = useCallback(() => {
    getTokenSummary().then(setSummary).catch(e => setError(e?.message || '加载汇总失败'));
  }, []);

  const loadDaily = useCallback((d: number) => {
    const req = ++dailyReq.current;
    setDailyLoading(true);
    setDailyError('');
    return getTokenDaily(d)
      .then(rows => {
        if (req === dailyReq.current) setDaily(rows || []);
      })
      .catch(e => {
        if (req === dailyReq.current) setDailyError(e?.message || '加载趋势失败');
      })
      .finally(() => {
        if (req === dailyReq.current) setDailyLoading(false);
      });
  }, []);

  const loadLogs = useCallback((p: number) => {
    const req = ++logsReq.current;
    setLogsError('');
    return getTokenLogs(p, size)
      .then(d => {
        if (req !== logsReq.current) return;
        setLogs(d.items || []);
        setTotalLogs(d.total || 0);
      })
      .catch(e => {
        if (req === logsReq.current) setLogsError(e?.message || '加载明细失败');
      });
  }, []);

  // 汇总与时间窗口无关，只加载一次
  useEffect(() => {
    loadSummary();
  }, [loadSummary]);

  // 窗口变化：回到第 1 页并同时重拉趋势与明细，避免页码与内容错位
  useEffect(() => {
    setPage(0);
    setLoading(true);
    loadDaily(days);
    getTokenByModel(days).then(setModelRows).catch(() => setModelRows([]));
    getTokenByCompany(days).then(setCompanyRows).catch(() => setCompanyRows([]));
    loadLogs(0).finally(() => setLoading(false));
  }, [loadDaily, loadLogs, days]);

  const totalPages = Math.max(1, Math.ceil(totalLogs / size));
  const hasMore = page < totalPages - 1;

  const goPage = (delta: number) => {
    const next = page + delta;
    if (next < 0 || next >= totalPages) return;
    setPage(next);
    loadLogs(next);
  };

  const dailyFilled = useMemo(() => fillDaily(daily, days), [daily, days]);
  const dailyHasData = daily.some(d => d.inputTokens > 0 || d.outputTokens > 0 || d.count > 0);

  if (loading && !summary) {
    return (
      <div className="min-h-screen bg-surface px-6 py-8">
        <div className="mx-auto max-w-[960px] text-sm text-muted-foreground">加载中...</div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <h1 className="text-2xl font-bold text-foreground mb-2">Token 用量统计</h1>
        <p className="text-xs text-muted-foreground mb-6">
          token 为估算值（中文约 1.5 字符 ≈ 1 token，误差约 ±30%），包含查询改写、报告生成等内部调用；
          无用户归属的调度调用只计入平台总量。超管切换企业后按所选企业统计。
        </p>

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
              {[7, 30, 90].map(d => (
                <button key={d} onClick={() => setDays(d)} disabled={dailyLoading}
                  className={`text-xs px-3 py-1 rounded-full disabled:opacity-50 ${days === d ? 'bg-primary text-white' : 'bg-surface-2 text-muted-foreground'}`}>
                  {d}天
                </button>
              ))}
            </div>
          </div>
          {/* 柱状图 */}
          <div className="px-5 py-4 min-h-[180px]">
            {dailyLoading && (
              <div className="text-xs text-muted-foreground">趋势加载中…</div>
            )}
            {!dailyLoading && dailyError && (
              <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 text-xs text-red-600 flex items-center gap-3">
                <span>{dailyError}</span>
                <button onClick={() => loadDaily(days)} className="underline">重试</button>
              </div>
            )}
            {!dailyLoading && !dailyError && !dailyHasData && (
              <div className="py-10 text-center text-sm text-muted-foreground">近 {days} 天没有调用记录</div>
            )}
            {!dailyLoading && !dailyError && dailyHasData && (
              <TokenBarChart data={dailyFilled} />
            )}
          </div>
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
                {!dailyHasData ? (
                  <tr><td colSpan={4} className="px-5 py-8 text-center text-muted-foreground">暂无数据</td></tr>
                ) : dailyFilled.map((d, i) => (
                  <tr key={d.date} className={`border-t border-border ${i % 2 === 0 ? 'bg-white' : 'bg-surface-2/30'}`}>
                    <td className="px-5 py-2.5 font-medium">{d.date}</td>
                    <td className="px-5 py-2.5 text-right tabular-nums">{exact(d.inputTokens)}</td>
                    <td className="px-5 py-2.5 text-right tabular-nums">{exact(d.outputTokens)}</td>
                    <td className="px-5 py-2.5 text-right tabular-nums">{d.count}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        {/* 模型分布 + 企业排行 */}
        <div className="grid gap-4 mb-8 md:grid-cols-2">
          <div className="bg-white rounded-xl border border-border p-5">
            <h2 className="font-semibold text-foreground mb-1">🧩 模型用量分布</h2>
            <p className="text-[11px] text-muted-foreground mb-4">近 {days} 天，按输入+输出 token 排序</p>
            <HBarList
              empty={`近 ${days} 天没有模型调用记录`}
              rows={modelRows.map(m => ({
                label: m.model || '未知模型',
                value: m.inputTokens + m.outputTokens,
                sub: `${m.count} 次`,
              }))}
              color="#2147ff"
            />
          </div>
          <div className="bg-white rounded-xl border border-border p-5">
            <h2 className="font-semibold text-foreground mb-1">🏢 企业用量排行</h2>
            <p className="text-[11px] text-muted-foreground mb-4">
              {companyRows.length > 0 ? `近 ${days} 天用量前 5` : '仅平台超级管理员可见'}
            </p>
            <HBarList
              empty={companyRows.length > 0 ? '暂无企业数据' : '当前账号无跨企业视角'}
              rows={companyRows.map(c => ({
                label: c.companyName,
                value: c.inputTokens + c.outputTokens,
                sub: `${c.count} 次`,
              }))}
              color="#ff4d5f"
            />
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
                    <td className="px-5 py-2.5 text-muted-foreground">
                      {l.userName || (l.userId ? l.userId.substring(0, 8) : '-')}
                    </td>
                    <td className="px-5 py-2.5">
                      <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-medium ${
                        l.modelType === 'CHAT' ? 'bg-blue-100 text-blue-700' : 'bg-amber-100 text-amber-700'
                      }`}>
                        {l.modelName || l.modelType}
                      </span>
                    </td>
                    <td className="px-5 py-2.5 text-right tabular-nums">{exact(l.inputTokens)}</td>
                    <td className="px-5 py-2.5 text-right tabular-nums">{exact(l.outputTokens)}</td>
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

/** 横向条形排行：一眼看出占比，hover 显示精确值 */
function HBarList({ rows, color, empty }: {
  rows: Array<{ label: string; value: number; sub?: string }>;
  color: string;
  empty: string;
}) {
  if (rows.length === 0) {
    return <p className="py-10 text-center text-sm text-muted-foreground">{empty}</p>;
  }
  const max = Math.max(...rows.map(r => r.value), 1);
  return (
    <div className="space-y-3" role="list" aria-label="用量排行">
      {rows.map(r => (
        <div key={r.label} role="listitem" title={`${r.label}：${exact(r.value)} token${r.sub ? ` · ${r.sub}` : ''}`}>
          <div className="mb-1 flex items-baseline justify-between gap-3">
            <span className="truncate text-xs font-medium text-foreground">{r.label}</span>
            <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
              {fmt(r.value)}{r.sub ? ` · ${r.sub}` : ''}
            </span>
          </div>
          <div className="h-2.5 w-full overflow-hidden rounded-full bg-surface-2">
            <div
              className="h-full rounded-full transition-all"
              style={{ width: `${Math.max((r.value / max) * 100, r.value > 0 ? 2 : 0)}%`, background: color }}
            />
          </div>
        </div>
      ))}
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

/** 并排柱状图：输入/输出同基线，支持鼠标、键盘、触屏查看精确数值 */
function TokenBarChart({ data }: { data: DailyTokenRow[] }) {
  const [hover, setHover] = useState<number | null>(null);
  const maxVal = Math.max(...data.map(d => Math.max(d.inputTokens, d.outputTokens)), 1);
  const maxCount = Math.max(...data.map(d => d.count), 1);
  const axisW = 40;
  const groupW = 48;
  const barW = 14;
  const gap = 6;
  const h = 170;
  const baseline = h - 20;
  const plotH = baseline - 8;
  const w = Math.max(data.length * groupW + axisW, 320);

  const barY = (v: number) => baseline - Math.max((v / maxVal) * plotH, v > 0 ? 1 : 0);

  return (
    <div className="overflow-x-auto">
      <svg width={w} height={h} className="block" role="img" aria-label="Token 每日用量柱状图">
        {/* y 轴刻度与网格 */}
        {[0, 0.5, 1].map(pct => {
          const y = baseline - pct * plotH;
          return (
            <g key={pct}>
              <line x1={axisW} y1={y} x2={w} y2={y} stroke="#e5e7eb"
                strokeDasharray={pct === 0 ? 'none' : '3,3'} />
              <text x={axisW - 6} y={y + 3} textAnchor="end" fill="#9ca3af" fontSize={9}>
                {fmt(Math.round(maxVal * pct))}
              </text>
            </g>
          );
        })}
        {data.map((d, i) => {
          const x = axisW + i * groupW + 6;
          const inY = barY(d.inputTokens);
          const outY = barY(d.outputTokens);
          return (
            <g key={d.date}>
              <rect x={x} y={inY} width={barW} height={baseline - inY}
                fill="#2147ff" rx={2}>
                <title>{`${d.date} 输入 ${exact(d.inputTokens)} token`}</title>
              </rect>
              <rect x={x + barW + gap} y={outY} width={barW} height={baseline - outY}
                fill="#ff4d5f" rx={2}>
                <title>{`${d.date} 输出 ${exact(d.outputTokens)} token`}</title>
              </rect>
              {/* 整组透明命中区：柱宽只有 14px，直接 hover 柱子很难命中 */}
              <rect x={axisW + i * groupW} y={4} width={groupW} height={baseline}
                fill="transparent" tabIndex={0} role="img"
                aria-label={`${d.date} 输入 ${exact(d.inputTokens)}，输出 ${exact(d.outputTokens)}，共 ${d.count} 次调用`}
                className="cursor-pointer outline-none"
                onMouseEnter={() => setHover(i)}
                onMouseLeave={() => setHover(prev => (prev === i ? null : prev))}
                onFocus={() => setHover(i)}
                onBlur={() => setHover(null)}
                onPointerDown={() => setHover(prev => (prev === i ? null : i))}
              />
              <text x={x + barW + gap / 2} y={h - 4} textAnchor="middle" fill="#747f9e" fontSize={10}>
                {d.date.slice(5)}
              </text>
            </g>
          );
        })}
        {/* 调用次数折线（独立缩放，仅看趋势与波动） */}
        <polyline
          fill="none" stroke="#0f9d58" strokeWidth={1.5} opacity={0.85}
          points={data.map((d, i) =>
            `${axisW + i * groupW + groupW / 2},${baseline - (d.count / maxCount) * plotH * 0.9}`).join(' ')}
        />
        {data.map((d, i) => (
          <circle key={`c-${d.date}`} cx={axisW + i * groupW + groupW / 2}
            cy={baseline - (d.count / maxCount) * plotH * 0.9}
            r={d.count > 0 ? 2 : 0} fill="#0f9d58" />
        ))}
        {hover !== null && data[hover] && (() => {
          const d = data[hover];
          const left = axisW + hover * groupW + groupW / 2;
          const tipX = Math.min(Math.max(left, 70), w - 70);
          return (
            <g pointerEvents="none">
              <rect x={tipX - 66} y={4} width={132} height={58} rx={6} fill="#111827" opacity={0.92} />
              <text x={tipX} y={19} textAnchor="middle" fill="#ffffff" fontSize={10} fontWeight={600}>{d.date}</text>
              <text x={tipX} y={33} textAnchor="middle" fill="#c7d2fe" fontSize={10}>输入 {exact(d.inputTokens)}</text>
              <text x={tipX} y={46} textAnchor="middle" fill="#fecdd3" fontSize={10}>输出 {exact(d.outputTokens)}</text>
              <text x={tipX} y={57} textAnchor="middle" fill="#9ca3af" fontSize={9}>
                合计 {exact(d.inputTokens + d.outputTokens)} · {d.count} 次
              </text>
            </g>
          );
        })()}
      </svg>
      {/* 图例 */}
      <div className="flex items-center gap-4 justify-center mt-1 pb-2">
        <span className="flex items-center gap-1 text-xs text-muted-foreground">
          <span className="w-3 h-3 rounded-sm bg-[#2147ff] inline-block" /> 输入
        </span>
        <span className="flex items-center gap-1 text-xs text-muted-foreground">
          <span className="w-3 h-3 rounded-sm bg-[#ff4d5f] inline-block" /> 输出
        </span>
        <span className="flex items-center gap-1 text-xs text-muted-foreground">
          <span className="inline-block h-[2px] w-4 bg-[#0f9d58]" /> 调用次数（独立缩放）
        </span>
      </div>
    </div>
  );
}
