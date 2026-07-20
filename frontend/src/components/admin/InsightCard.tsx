'use client';

import React, { useState } from 'react';
import type { AutoInsight, CandidateGrain } from '@/lib/api/admin-insights';

const SEVERITY_CONFIG: Record<string, { bar: string; bg: string; icon: string; label: string }> = {
  critical: { bar: '#DC2626', bg: '#FEF2F2', icon: '🔴', label: '严重' },
  warning:  { bar: '#D97706', bg: '#FFFBEB', icon: '🟡', label: '警告' },
  info:     { bar: '#2563EB', bg: '#EFF6FF', icon: '🔵', label: '提示' },
};

const TYPE_LABELS: Record<string, string> = {
  gap_burst: '缺口爆发',
  satisfaction_drop: '满意率骤降',
  hit_rate_drop: '命中率下降',
  new_pattern: '发现新场景',
  inactive: '分身不活跃',
};

interface Props {
  insight: AutoInsight;
  candidateGrains?: CandidateGrain[];
  onApprove?: (grainId: string) => void;
  onReject?: (grainId: string) => void;
  onResolve?: (insightId: string) => void;
  onIgnore?: (insightId: string) => void;
}

/**
 * 自动发现洞察卡片 —— severity 左色条 + 白卡体。
 * 展开后显示候选颗粒审核区。
 */
export function InsightCard({ insight, candidateGrains, onApprove, onReject, onResolve, onIgnore }: Props) {
  const [expanded, setExpanded] = useState(false);
  const sev = SEVERITY_CONFIG[insight.severity] || SEVERITY_CONFIG.info;

  // 解析时间差
  const timeAgo = insight.createdAt ? getTimeAgo(new Date(insight.createdAt)) : '';

  // 解析证据 JSON
  let evidence: Record<string, unknown> = {};
  try {
    evidence = JSON.parse(insight.evidence || '{}');
  } catch { console.error('解析 insight evidence JSON 失败', insight.id); }

  return (
    <div
      className="rounded-[12px] bg-white border border-[#E8ECF1] shadow-[0_1px_2px_rgba(15,23,42,0.06)] overflow-hidden transition-all duration-150 hover:shadow-[0_8px_20px_rgba(15,23,42,0.08)]"
    >
      {/* 左色条 + 头部 */}
      <div className="flex">
        {/* severity 色条 */}
        <div className="w-1 flex-shrink-0" style={{ background: sev.bar }} />

        <div className="flex-1 min-w-0 p-5">
          {/* 标签行 */}
          <div className="flex items-center gap-2 mb-2">
            <span
              className="inline-flex items-center gap-1 text-[11px] font-medium px-2 py-0.5 rounded-full"
              style={{ background: sev.bg, color: sev.bar }}
            >
              {sev.icon} {sev.label}
            </span>
            <span className="text-[11px] text-[#94A3B8]">
              {TYPE_LABELS[insight.type] || insight.type}
            </span>
            {timeAgo && (
              <span className="text-[11px] text-[#CBD5E1]">· {timeAgo}</span>
            )}
          </div>

          {/* 标题 */}
          <h4 className="text-[15px] font-semibold text-[#1E293B] mb-2 leading-snug">
            {insight.title}
          </h4>

          {/* 描述 */}
          {insight.description && (
            <p className="text-[13px] text-[#64748B] leading-relaxed mb-3">
              {insight.description}
            </p>
          )}

          {/* 数据 snippet */}
          {evidence.total_attempts != null && (
            <p className="text-[12px] text-[#94A3B8] mb-3">
              累计提问 {String(evidence.total_attempts)} 次
              {evidence.member_count != null && <> · 涉及 {String(evidence.member_count)} 个缺口</>}
              {(() => {
                const samples = evidence.sample_queries;
                if (Array.isArray(samples) && samples.length > 0) {
                  return <> · 代表问题：「{String(samples[0])}」</>;
                }
                return null;
              })()}
            </p>
          )}

          {/* 操作行 */}
          <div className="flex items-center gap-3">
            <button
              onClick={() => setExpanded(!expanded)}
              className="text-[12px] font-medium text-[#2563EB] hover:underline"
            >
              {expanded ? '收起证据 ▾' : '展开证据 ▸'}
            </button>

            <div className="flex-1" />

            {onResolve && (
              <button
                onClick={() => onResolve(insight.id)}
                className="text-[12px] font-medium text-[#16A34A] hover:opacity-80 transition-opacity"
              >
                已处理
              </button>
            )}
            {onIgnore && (
              <button
                onClick={() => onIgnore(insight.id)}
                className="text-[12px] font-medium text-[#94A3B8] hover:text-[#64748B] transition-colors"
              >
                忽略
              </button>
            )}
          </div>
        </div>
      </div>

      {/* 展开：证据 + 候选颗粒 */}
      {expanded && (
        <div className="border-t border-[#E8ECF1] bg-[#F8FAFC]">
          {/* 证据详情 */}
          <div className="px-5 py-3">
            <p className="text-[11px] font-bold text-[#64748B] uppercase tracking-[0.04em] mb-2">
              证据详情
            </p>
            <div className="text-[12px] text-[#475569] space-y-1">
              {Object.entries(evidence).map(([k, v]) => (
                <div key={k} className="flex gap-2">
                  <span className="text-[#94A3B8] font-medium flex-shrink-0">{k}:</span>
                  <span className="break-all">
                    {Array.isArray(v) ? (v as string[]).join(', ') : String(v)}
                  </span>
                </div>
              ))}
            </div>
          </div>

          {/* 候选颗粒审核区 */}
          {candidateGrains && candidateGrains.length > 0 && (
            <div className="px-5 py-3 border-t border-[#E8ECF1]">
              <p className="text-[11px] font-bold text-[#64748B] uppercase tracking-[0.04em] mb-3">
                AI 生成的候选颗粒 · {candidateGrains.length} 条
              </p>
              {candidateGrains.map(grain => (
                <CandidateGrainCard
                  key={grain.id}
                  grain={grain}
                  onApprove={onApprove}
                  onReject={onReject}
                />
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** 候选颗粒卡片 —— 四字段网格 + 审核按钮 */
function CandidateGrainCard({
  grain, onApprove, onReject,
}: {
  grain: CandidateGrain;
  onApprove?: (id: string) => void;
  onReject?: (id: string) => void;
}) {
  const isPending = grain.status === 'pending_review';

  return (
    <div className="rounded-lg bg-white border border-[#E8ECF1] p-4 mb-2.5 last:mb-0">
      {/* 场景标签 + 状态 */}
      <div className="flex items-center gap-2 mb-3">
        <span className="inline-flex items-center gap-1 text-[11px] font-medium text-[#2563EB] bg-[#EFF6FF] rounded-full px-2.5 py-0.5">
          {grain.sceneTag}
        </span>
        <span className={`text-[11px] font-medium ${
          grain.status === 'approved' ? 'text-[#16A34A]'
          : grain.status === 'rejected' ? 'text-[#DC2626]'
          : 'text-[#D97706]'
        }`}>
          {grain.status === 'approved' ? '✅ 已通过'
           : grain.status === 'rejected' ? '❌ 已拒绝'
           : '⏳ 待审核'}
        </span>
      </div>

      {/* 四字段网格 */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-[13px] mb-3">
        <Field label="专家思考" value={grain.expertThought} />
        <Field label="标准话术" value={grain.standardScript} />
        <Field label="常见错误" value={grain.commonMistakes} />
        <Field label="适用条件" value={grain.applicableCondition} />
      </div>

      {/* 证据 */}
      {grain.sourceEvidence && (
        <div className="text-[11px] text-[#94A3B8] mb-3">
          数据依据：{(() => {
            try {
              const ev = JSON.parse(grain.sourceEvidence);
              const parts: string[] = [];
              if (ev.total_attempts) parts.push(`累计 ${ev.total_attempts} 次提问`);
              if (ev.member_count) parts.push(`涉及 ${ev.member_count} 个缺口`);
              return parts.join(' · ') || '—';
            } catch { console.error('解析 candidate grain evidence 失败', grain.id); return '—'; }
          })()}
        </div>
      )}

      {/* 审核按钮 */}
      {isPending && (onApprove || onReject) && (
        <div className="flex items-center gap-2 justify-end border-t border-[#E8ECF1] pt-3">
          {onReject && (
            <button
              onClick={() => onReject(grain.id)}
              className="text-[12px] font-medium px-4 py-1.5 rounded-lg border border-[#E8ECF1] text-[#64748B] hover:bg-[#F1F5F9] transition-colors"
            >
              拒绝
            </button>
          )}
          {onApprove && (
            <button
              onClick={() => onApprove(grain.id)}
              className="text-[12px] font-medium px-4 py-1.5 rounded-lg text-white transition-colors"
              style={{ background: 'linear-gradient(135deg, #2563EB, #1D4ED8)' }}
            >
              审核通过 · 入库
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function Field({ label, value }: { label: string; value?: string }) {
  if (!value) return null;
  return (
    <div>
      <p className="text-[11px] font-semibold text-[#94A3B8] uppercase tracking-[0.03em] mb-0.5">{label}</p>
      <p className="text-[13px] text-[#334155] leading-relaxed line-clamp-4">{value}</p>
    </div>
  );
}

function getTimeAgo(date: Date): string {
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffHrs = Math.floor(diffMs / 3600000);
  if (diffHrs < 1) return '刚刚';
  if (diffHrs < 24) return `${diffHrs} 小时前`;
  const diffDays = Math.floor(diffHrs / 24);
  if (diffDays < 7) return `${diffDays} 天前`;
  return date.toLocaleDateString('zh-CN');
}
