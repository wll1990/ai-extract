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
    <section className="rounded border border-gray-200 p-4">
      <h3 className="mb-3 text-sm font-medium text-gray-900">{title}</h3>
      {points.length === 0 && <p className="text-sm text-gray-500">{emptyText}</p>}
      <ul className="space-y-3">
        {points.map((p, i) => (
          <li key={i} className="border-l-2 border-gray-200 pl-3">
            <div className="flex items-start justify-between gap-2">
              <p className="text-sm text-gray-900">{p.point}</p>
              <span className="shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600">
                置信度 {CONFIDENCE[p.confidence] || p.confidence}
              </span>
            </div>
            {p.suggestion && <p className="mt-1 text-xs text-gray-600">建议：{p.suggestion}</p>}
            <button
              type="button"
              onClick={() => setCitation(p.evidence)}
              className="mt-2 text-xs text-blue-600 hover:underline"
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
      <div className="rounded border border-gray-200 p-4">
        <div className="flex items-center gap-2">
          <span className="text-sm text-gray-700">报告状态</span>
          <StatusBadge status={report.status} />
        </div>
        {report.status === 'failed' && (
          <p className="mt-2 text-sm text-red-600">
            生成失败：{report.errorMessage || '未知原因'}
          </p>
        )}
        {report.status === 'ready' && !report.payload && (
          <p className="mt-2 text-sm text-red-600">
            {report.errorMessage || '报告内容解析失败，请重新生成'}
          </p>
        )}
      </div>
    );
  }
  const p = report.payload;
  return (
    <div className="space-y-4">
      <section className="rounded border border-blue-100 bg-blue-50 p-4">
        <h2 className="mb-1 text-sm font-medium text-blue-900">一句话结论</h2>
        <p className="text-sm text-blue-900">{p.summary}</p>
        {p.summaryEvidence && p.summaryEvidence.length > 0 && (
          <button
            type="button"
            onClick={() => setCitation(p.summaryEvidence!)}
            className="mt-2 text-xs text-blue-600 hover:underline"
          >
            查看引用（{p.summaryEvidence.length}）
          </button>
        )}
      </section>

      {p.primaryAction && (
        <section className="rounded border border-emerald-200 bg-emerald-50 p-4">
          <h2 className="mb-1 text-sm font-medium text-emerald-900">下次通话先做这一件事</h2>
          <p className="text-sm font-medium text-emerald-950">{p.primaryAction.title}</p>
          {p.primaryAction.content && (
            <p className="mt-1 text-sm text-emerald-900">照着说：{p.primaryAction.content}</p>
          )}
          <div className="mt-1 text-xs text-emerald-800/70">
            {[
              p.primaryAction.channel,
              p.primaryAction.dueInDays != null ? `${p.primaryAction.dueInDays} 天内` : null,
            ].filter(Boolean).join(' · ')}
          </div>
          {p.primaryAction.evidence && p.primaryAction.evidence.length > 0 && (
            <button
              type="button"
              onClick={() => setCitation(p.primaryAction!.evidence!)}
              className="mt-2 text-xs text-emerald-700 hover:underline"
            >
              查看引用（{p.primaryAction.evidence.length}）
            </button>
          )}
        </section>
      )}

      {p.infoGaps && p.infoGaps.length > 0 && (
        <section className="rounded border border-amber-200 bg-amber-50 p-4">
          <h2 className="mb-2 text-sm font-medium text-amber-900">本次没拿到，下次必须问</h2>
          <ul className="space-y-2">
            {p.infoGaps.map((g, i) => (
              <li key={i} className="text-sm text-amber-950">
                <span className="mr-1 rounded bg-amber-100 px-1.5 py-0.5 text-xs text-amber-800">
                  {g.field}
                </span>
                {g.question}
                {g.why && <span className="mt-0.5 block text-xs text-amber-800/80">{g.why}</span>}
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

      <section className="rounded border border-gray-200 p-4">
        <h3 className="mb-3 text-sm font-medium text-gray-900">客户信号</h3>
        {p.customerSignals.length === 0 && <p className="text-sm text-gray-500">没有识别到客户信号。</p>}
        <dl className="grid grid-cols-2 gap-y-2 text-sm">
          {p.customerSignals.map((s, i) => (
            <div key={i} className="col-span-2 grid grid-cols-3 gap-2 border-b border-gray-100 pb-2">
              <dt className="text-gray-500">{s.field}</dt>
              <dd className={s.value ? 'text-gray-900' : 'text-gray-400'}>{s.value || '未提及'}</dd>
              <dd className="text-xs text-gray-500">
                {SOURCE[s.sourceType]} · 置信度 {CONFIDENCE[s.confidence]}
                {s.evidence && s.evidence.length > 0 && (
                  <button
                    type="button"
                    onClick={() => setCitation(s.evidence)}
                    className="ml-2 text-blue-600 hover:underline"
                  >
                    查看引用（{s.evidence.length}）
                  </button>
                )}
              </dd>
            </div>
          ))}
        </dl>
      </section>

      <section className="rounded border border-gray-200 p-4">
        <h3 className="mb-3 text-sm font-medium text-gray-900">下一步</h3>
        {p.nextSteps.length === 0 && <p className="text-sm text-gray-500">没有生成下一步建议。</p>}
        <ol className="list-decimal space-y-2 pl-5 text-sm text-gray-800">
          {p.nextSteps.map((s, i) => (
            <li key={i}>
              <span className="font-medium">{s.title}</span>
              {s.channel && <span className="ml-2 text-xs text-gray-500">{s.channel}</span>}
              {s.dueInDays != null && (
                <span className="ml-2 text-xs text-gray-500">{s.dueInDays} 天内</span>
              )}
              {s.content && <p className="mt-0.5 text-xs text-gray-600">{s.content}</p>}
              {s.evidence && s.evidence.length > 0 && (
                <button
                  type="button"
                  onClick={() => setCitation(s.evidence!)}
                  className="mt-1 text-xs text-blue-600 hover:underline"
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
