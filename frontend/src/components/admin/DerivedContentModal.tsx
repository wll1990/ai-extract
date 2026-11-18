'use client';

import React from 'react';
import Link from 'next/link';
import type { DerivedContent, DerivedExamItem } from '@/lib/api/content';
import { Button } from '@/components/ui/Button';
import { CopyButton } from '@/components/ui/CopyButton';

interface Props {
  open: boolean;
  source: DerivedContent | null;
  sourceType: 'knowledge' | 'case';
  onClose: () => void;
}

function examText(item: DerivedExamItem): string {
  return [
    `题目：${item.question}`,
    item.context ? `背景：${item.context}` : '',
    `期望要点：\n${(item.expectedPoints ?? []).map(p => `- ${p}`).join('\n')}`,
    item.forbiddenPoints?.length ? `禁止要点：\n${item.forbiddenPoints.map(p => `- ${p}`).join('\n')}` : '',
    item.scoringRule ? `评分规则：${item.scoringRule}` : '',
    item.goldBasis ? `金标依据：${item.goldBasis}` : '',
  ].filter(Boolean).join('\n');
}

/**
 * AI 派生结果弹窗。
 *
 * <p>价值口径：知识来源只产「评测题 + 微练」（知识再派生 FAQ 回知识库是重复）；
 * 案例来源额外产「知识卡」，把一次成交/丢单沉淀成可复用打法。
 * 结果只存在生成任务里，不自动落库，因此每个区块都写明复制后粘贴到哪里。</p>
 */
export function DerivedContentModal({ open, source, sourceType, onClose }: Props) {
  if (!open || !source) return null;

  const isCase = sourceType === 'case';
  const cards = isCase ? (source.faqs ?? []) : [];
  const examItems = source.examItems ?? [];
  const drill = source.drill;
  const drillQuestions = drill?.questions ?? [];

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4 sm:p-6" onClick={onClose}>
      <div className="max-h-[85vh] w-full max-w-[680px] overflow-y-auto rounded-xl bg-surface p-4 shadow-xl sm:p-6"
        onClick={e => e.stopPropagation()}>
        <div className="mb-1 flex flex-wrap items-start justify-between gap-2">
          <h3 className="text-lg font-semibold text-foreground">AI 派生结果</h3>
          <CopyButton
            text={JSON.stringify(source, null, 2)}
            label="复制全部 JSON"
            variant="ghost"
            size="sm"
            title="供外部工具使用；页面内请按下方提示逐项复制"
          />
        </div>
        <p className="mb-4 text-xs leading-5 text-muted-foreground">
          结果只保存在生成任务里，不会自动入库或生成草稿；关闭后当前弹窗不再展示，请先复制需要的内容。
          生成内容请人工检查后再使用。
        </p>

        {cards.length > 0 && (
          <section className="mb-5">
            <div className="mb-1 flex flex-wrap items-center justify-between gap-2">
              <p className="text-sm font-semibold text-foreground">知识卡（{cards.length}）</p>
              <CopyButton
                text={cards.map(f => `场景/问题：${f.question}\n可复用打法：${f.answer}`).join('\n\n')}
                label="复制知识卡"
              />
            </div>
            <p className="mb-2 text-[11px] leading-5 text-muted-foreground">
              粘贴到：<Link href="/admin/knowledge/new" target="_blank" rel="noreferrer" className="text-primary hover:underline">知识库 → 新增知识</Link>
              ；标题填「场景 + 问题」，正文填可复用打法。
            </p>
            <div className="space-y-2">
              {cards.map((f, i) => (
                <div key={i} className="rounded-lg border border-border bg-surface-2 px-3 py-2 text-sm">
                  <p className="font-medium text-foreground">{f.question}</p>
                  <p className="mt-0.5 text-xs text-muted-foreground">{f.answer}</p>
                </div>
              ))}
            </div>
          </section>
        )}

        {examItems.length > 0 && (
          <section className="mb-5">
            <div className="mb-1 flex flex-wrap items-center justify-between gap-2">
              <p className="text-sm font-semibold text-foreground">评测题（{examItems.length}）</p>
              <CopyButton
                text={examItems.map(examText).join('\n\n———\n\n')}
                label="复制全部题目"
              />
            </div>
            <p className="mb-2 text-[11px] leading-5 text-muted-foreground">
              粘贴到：<Link href="/admin/evaluations" target="_blank" rel="noreferrer" className="text-primary hover:underline">评测中心</Link>
              → 打开草稿题集 → 新增题目，按「题目 / 背景 / 期望要点 / 禁止要点 / 评分规则」逐项粘贴。
            </p>
            <div className="space-y-2">
              {examItems.map((item, i) => (
                <div key={i} className="rounded-lg border border-border bg-surface-2 px-3 py-2 text-sm">
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <p className="min-w-0 flex-1 font-medium text-foreground">{item.question}</p>
                    <CopyButton text={examText(item)} label="复制本题" size="sm" />
                  </div>
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
          <section className="mb-5">
            <div className="mb-1 flex flex-wrap items-center justify-between gap-2">
              <p className="text-sm font-semibold text-foreground">场景微练</p>
              <CopyButton
                text={[
                  `场景：${drill.scenario}`,
                  drill.clientRole ? `客户角色：${drill.clientRole}` : '',
                  drillQuestions.length
                    ? `练习问题：\n${drillQuestions.map((q, i) => `${i + 1}. ${q.question}`).join('\n')}`
                    : '',
                ].filter(Boolean).join('\n')}
                label="复制微练"
              />
            </div>
            <p className="mb-2 text-[11px] leading-5 text-muted-foreground">
              用途：<Link href="/admin/training/plans" target="_blank" rel="noreferrer" className="text-primary hover:underline">培训计划</Link>
              → 添加「微练」任务。注意：当前微练内容只能由页面内「生成练习内容」写入，暂不支持粘贴；
              这里的结果可复制留存或供外部使用。
            </p>
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

        {cards.length === 0 && examItems.length === 0 && !drill && (
          <p className="text-sm text-muted-foreground">模型没有返回可用内容，请稍后重试。</p>
        )}

        <div className="mt-4 flex justify-end">
          <Button variant="neutral" onClick={onClose}>关闭</Button>
        </div>
      </div>
    </div>
  );
}
