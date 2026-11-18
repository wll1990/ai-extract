'use client';

import { useState } from 'react';
import { ReviewPoint, ReviewReport, ReviewSegment } from '@/lib/api/sales';
import { CitationPopover } from './CitationPopover';
import { StatusBadge } from './StatusBadge';

const CONFIDENCE: Record<string, string> = { high: '高', medium: '中', low: '低' };
const SOURCE: Record<string, string> = {
  customer_words: '客户原话',
  sales_paraphrase: '销售转述',
  ai_inference: 'AI 推断',
  unknown: '未知',
};

function Section({
  title,
  points,
  segments,
  emptyText,
}: {
  title: string;
  points: ReviewPoint[];
  segments: ReviewSegment[];
  emptyText: string;
}) {
  const [citation, setCitation] = useState<ReviewPoint['evidence'] | null>(null);
  return (
    <section className="rounded-xl border border-border bg-surface-2 p-4">
      <h3 className="mb-3 text-sm font-medium text-foreground">{title}</h3>
      {points.length === 0 && <p className="text-sm text-muted-foreground">{emptyText}</p>}
      <ul className="space-y-3">
        {points.map((p, i) => (
          <li key={i} className="border-l-2 border-border-strong pl-3">
            <div className="flex items-start justify-between gap-2">
              <p className="text-sm text-foreground">{p.point}</p>
              <span className="shrink-0 rounded bg-surface px-1.5 py-0.5 text-xs text-muted-foreground">
                置信度 {CONFIDENCE[p.confidence] || p.confidence}
              </span>
            </div>
            {p.suggestion && <p className="mt-1 text-xs text-muted-foreground">建议：{p.suggestion}</p>}
            <button
              type="button"
              onClick={() => setCitation(p.evidence)}
              className="mt-2 text-xs text-primary hover:underline" title="展开这条判断在原文中的依据"
            >
              查看引用（{p.evidence.length}）
            </button>
          </li>
        ))}
      </ul>
      {citation && (
        <CitationPopover evidence={citation} segments={segments} onClose={() => setCitation(null)} />
      )}
    </section>
  );
}

export function ReviewReportPanel({
  report,
  segments,
}: {
  report: ReviewReport;
  segments: ReviewSegment[];
}) {
  const [citation, setCitation] = useState<Array<{ segmentId: string; quote?: string }> | null>(null);

  if (report.status !== 'ready' || !report.payload) {
    return (
      <div className="rounded-xl border border-border bg-surface-2 p-4">
        <div className="flex items-center gap-2">
          <span className="text-sm text-foreground">报告状态</span>
          <StatusBadge status={report.status} />
        </div>
        {report.status === 'failed' && (
          <p className="mt-2 text-sm text-danger">
            生成失败：{report.errorMessage || '未知原因'}
          </p>
        )}
        {report.status === 'ready' && !report.payload && (
          <p className="mt-2 text-sm text-danger">
            {report.errorMessage || '报告内容解析失败，请重新生成'}
          </p>
        )}
      </div>
    );
  }
  const p = report.payload;
  return (
    <div className="space-y-4">
      <section className="rounded border border-primary/30 bg-primary-light/40 p-4">
        <h2 className="mb-1 text-sm font-medium text-foreground">一句话结论</h2>
        <p className="text-sm text-foreground">{p.summary}</p>
        {p.summaryEvidence && p.summaryEvidence.length > 0 && (
          <button
            type="button"
            onClick={() => setCitation(p.summaryEvidence!)}
            className="mt-2 text-xs text-primary hover:underline" title="展开这条判断在原文中的依据"
          >
            查看引用（{p.summaryEvidence.length}）
          </button>
        )}
      </section>

      {p.primaryAction && (
        <section className="rounded border border-success/30 bg-success-bg p-4">
          <h2 className="mb-1 text-sm font-medium text-foreground">下次通话先做这一件事</h2>
          <p className="text-sm font-medium text-foreground">{p.primaryAction.title}</p>
          {p.primaryAction.content && (
            <p className="mt-1 text-sm text-foreground">照着说：{p.primaryAction.content}</p>
          )}
          <div className="mt-1 text-xs text-muted-foreground">
            {[
              p.primaryAction.channel,
              p.primaryAction.dueInDays != null ? `${p.primaryAction.dueInDays} 天内` : null,
            ].filter(Boolean).join(' · ')}
          </div>
          {p.primaryAction.evidence && p.primaryAction.evidence.length > 0 && (
            <button
              type="button"
              onClick={() => setCitation(p.primaryAction!.evidence!)}
              className="mt-2 text-xs text-success hover:underline"
            >
              查看引用（{p.primaryAction.evidence.length}）
            </button>
          )}
        </section>
      )}

      {p.infoGaps && p.infoGaps.length > 0 && (
        <section className="rounded border border-warning-text/30 bg-warning-bg p-4">
          <h2 className="mb-2 text-sm font-medium text-warning-text">本次没拿到，下次必须问</h2>
          <ul className="space-y-2">
            {p.infoGaps.map((g, i) => (
              <li key={i} className="text-sm text-warning-text">
                <span className="mr-1 rounded bg-warning-bg px-1.5 py-0.5 text-xs text-warning-text">
                  {g.field}
                </span>
                {g.question}
                {g.why && <span className="mt-0.5 block text-xs text-warning-text/80">{g.why}</span>}
              </li>
            ))}
          </ul>
        </section>
      )}

      <Section title="做得好的" points={p.strengths} segments={segments}
        emptyText="本次对话没有识别到可引用的亮点。" />
      <Section title="不足" points={p.weaknesses} segments={segments}
        emptyText="本次对话没有识别到可引用的不足。" />
      <Section title="风险" points={p.risks} segments={segments}
        emptyText="本次对话没有识别到可引用的风险。" />

      <section className="rounded-xl border border-border bg-surface-2 p-4">
        <h3 className="mb-3 text-sm font-medium text-foreground">客户信号</h3>
        {p.customerSignals.length === 0 && <p className="text-sm text-muted-foreground">没有识别到客户信号。</p>}
        <dl className="grid grid-cols-2 gap-y-2 text-sm">
          {p.customerSignals.map((s, i) => (
            <div key={i} className="col-span-2 grid grid-cols-3 gap-2 border-b border-border pb-2">
              <dt className="text-muted-foreground">{s.field}</dt>
              <dd className={s.value ? 'text-foreground' : 'text-muted-foreground-2'}>{s.value || '未提及'}</dd>
              <dd className="text-xs text-muted-foreground">
                {SOURCE[s.sourceType]} · 置信度 {CONFIDENCE[s.confidence]}
                {s.evidence && s.evidence.length > 0 && (
                  <button
                    type="button"
                    onClick={() => setCitation(s.evidence)}
                    className="ml-2 text-primary hover:underline"
                  >
                    查看引用（{s.evidence.length}）
                  </button>
                )}
              </dd>
            </div>
          ))}
        </dl>
      </section>

      <section className="rounded-xl border border-border bg-surface-2 p-4">
        <h3 className="mb-3 text-sm font-medium text-foreground">下一步</h3>
        {p.nextSteps.length === 0 && <p className="text-sm text-muted-foreground">没有生成下一步建议。</p>}
        <ol className="list-decimal space-y-2 pl-5 text-sm text-foreground">
          {p.nextSteps.map((s, i) => (
            <li key={i}>
              <span className="font-medium">{s.title}</span>
              {s.channel && <span className="ml-2 text-xs text-muted-foreground">{s.channel}</span>}
              {s.dueInDays != null && (
                <span className="ml-2 text-xs text-muted-foreground">{s.dueInDays} 天内</span>
              )}
              {s.content && <p className="mt-0.5 text-xs text-muted-foreground">{s.content}</p>}
              {s.evidence && s.evidence.length > 0 && (
                <button
                  type="button"
                  onClick={() => setCitation(s.evidence!)}
                  className="mt-1 text-xs text-primary hover:underline"
                >
                  查看引用（{s.evidence.length}）
                </button>
              )}
            </li>
          ))}
        </ol>
      </section>

      {citation && (
        <CitationPopover evidence={citation} segments={segments} onClose={() => setCitation(null)} />
      )}
    </div>
  );
}
