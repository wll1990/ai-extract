'use client';

import React from 'react';
import type { DerivedContent } from '@/lib/api/content';

interface Props {
  open: boolean;
  source: DerivedContent | null;
  onClose: () => void;
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    alert('复制失败，请手动选择文本复制');
  }
}

/**
 * 一源多用结果弹窗：FAQ / 评测题 / 场景微练。
 * 派生结果不自动入库，用户确认后复制到对应模块。
 */
export function DerivedContentModal({ open, source, onClose }: Props) {
  if (!open || !source) return null;

  const faqs = source.faqs ?? [];
  const examItems = source.examItems ?? [];
  const drill = source.drill;
  const drillQuestions = drill?.questions ?? [];

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-6" onClick={onClose}>
      <div className="max-h-[85vh] w-full max-w-[680px] overflow-y-auto rounded-xl bg-surface p-6 shadow-xl"
        onClick={e => e.stopPropagation()}>
        <div className="mb-1 flex items-start justify-between">
          <h3 className="text-lg font-semibold text-foreground">一源多用（AI 派生）</h3>
          <button onClick={() => copyText(JSON.stringify(source, null, 2))}
            className="text-xs text-primary hover:underline">复制全部 JSON</button>
        </div>
        <p className="mb-4 text-xs text-muted-foreground">
          派生结果不会自动入库；确认后复制到知识、题库或培训微练。内容仅基于本条材料。
        </p>

        {faqs.length > 0 && (
          <section className="mb-4">
            <div className="mb-1 flex items-center justify-between">
              <p className="text-sm font-semibold text-foreground">FAQ（{faqs.length}）</p>
              <button onClick={() => copyText(faqs.map(f => `Q：${f.question}\nA：${f.answer}`).join('\n\n'))}
                className="text-xs text-primary hover:underline">复制 FAQ</button>
            </div>
            <div className="space-y-2">
              {faqs.map((f, i) => (
                <div key={i} className="rounded-lg border border-border bg-surface-2 px-3 py-2 text-sm">
                  <p className="font-medium text-foreground">{f.question}</p>
                  <p className="mt-0.5 text-xs text-muted-foreground">{f.answer}</p>
                </div>
              ))}
            </div>
          </section>
        )}

        {examItems.length > 0 && (
          <section className="mb-4">
            <div className="mb-1 flex items-center justify-between">
              <p className="text-sm font-semibold text-foreground">评测题（{examItems.length}）</p>
              <button onClick={() => copyText(JSON.stringify(examItems, null, 2))}
                className="text-xs text-primary hover:underline">复制题目 JSON</button>
            </div>
            <div className="space-y-2">
              {examItems.map((item, i) => (
                <div key={i} className="rounded-lg border border-border bg-surface-2 px-3 py-2 text-sm">
                  <p className="font-medium text-foreground">{item.question}</p>
                  {item.context && <p className="mt-0.5 text-xs text-muted-foreground">背景：{item.context}</p>}
                  <div className="mt-1 flex flex-wrap gap-1">
                    {(item.expectedPoints ?? []).map((p, j) => (
                      <span key={j} className="rounded-full bg-primary-light px-2 py-0.5 text-[10px] text-primary">{p}</span>
                    ))}
                  </div>
                  {item.scoringRule && <p className="mt-1 text-xs text-muted-foreground">评分：{item.scoringRule}</p>}
                  {item.goldBasis && <p className="mt-0.5 text-xs text-muted-foreground">金标依据：{item.goldBasis}</p>}
                </div>
              ))}
            </div>
          </section>
        )}

        {drill && (
          <section className="mb-4">
            <div className="mb-1 flex items-center justify-between">
              <p className="text-sm font-semibold text-foreground">场景微练</p>
              <button onClick={() => copyText(JSON.stringify(drill, null, 2))}
                className="text-xs text-primary hover:underline">复制微练 JSON</button>
            </div>
            <div className="rounded-lg border border-border bg-surface-2 px-3 py-2 text-sm">
              <p className="text-foreground">{drill.scenario}</p>
              {drill.clientRole && (
                <p className="mt-0.5 text-xs text-muted-foreground">客户角色：{drill.clientRole}</p>
              )}
              {drillQuestions.length > 0 && (
                <ul className="mt-1 list-disc pl-4 text-xs text-muted-foreground">
                  {drillQuestions.map((q, i) => <li key={i}>{q.question}</li>)}
                </ul>
              )}
            </div>
          </section>
        )}

        {faqs.length === 0 && examItems.length === 0 && !drill && (
          <p className="text-sm text-muted-foreground">模型没有返回可用内容，请稍后重试。</p>
        )}

        <div className="mt-4 flex justify-end">
          <button onClick={onClose}
            className="rounded-full border border-border px-4 py-1.5 text-sm text-foreground hover:bg-primary-light">
            关闭
          </button>
        </div>
      </div>
    </div>
  );
}
