'use client';

import React, { useState } from 'react';
import { retrievalTest, type RetrievalTestResult } from '@/lib/api/knowledge';
import { Button } from '@/components/ui/Button';

interface Props {
  open: boolean;
  scope: 'knowledge' | 'case';
  onClose: () => void;
}

const EXAMPLES: Record<Props['scope'], string[]> = {
  knowledge: ['客户说太贵了', '预算还没批', '和竞品比有什么优势', '客户骂我'],
  case: ['客户拿竞品压价', '预算没批怎么推进', '客户一直说再考虑', '价格谈判怎么收尾'],
};

/**
 * 检索测试弹窗：知识库只测知识，案例库只测案例。
 *
 * <p>诊断模式：低于线上阈值的「近失命中」也会列出并标「未达阈值」，
 * 只有标「会进入分身」的内容才会真正进入回答。</p>
 */
export function RetrievalTestModal({ open, scope, onClose }: Props) {
  const [query, setQuery] = useState('');
  const [result, setResult] = useState<RetrievalTestResult | null>(null);
  const [testing, setTesting] = useState(false);

  if (!open) return null;

  const isKnowledge = scope === 'knowledge';
  const threshold = isKnowledge
    ? (result?.knowledgeThreshold ?? 0.45)
    : (result?.caseThreshold ?? 0.50);
  const hits = isKnowledge ? (result?.knowledge ?? []) : (result?.cases ?? []);
  const effectiveCount = hits.filter(h => h.effective).length;

  const run = async () => {
    if (!query.trim()) return;
    setTesting(true);
    try {
      setResult(await retrievalTest(query.trim(), 5, scope));
    } catch (e) {
      alert(e instanceof Error ? e.message : '检索测试失败');
    } finally {
      setTesting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center bg-black/40 p-4 pt-16" onClick={onClose}>
      <div className="max-h-[84vh] w-full max-w-2xl overflow-y-auto rounded-2xl bg-surface p-6 shadow-xl"
        onClick={e => e.stopPropagation()}>
        <div className="mb-3 flex items-start justify-between gap-4">
          <div>
            <h2 className="text-lg font-bold text-foreground">
              检索测试 · {isKnowledge ? '知识' : '案例'}
            </h2>
            <p className="mt-1 text-xs leading-5 text-muted-foreground">
              诊断模式：低于线上阈值的「近失命中」也会列出来，方便判断为什么没被引用。
              只有标绿的内容才会真正进入分身回答。
            </p>
            <p className="mt-1 text-xs text-muted-foreground">
              线上阈值：{isKnowledge ? '知识' : '案例'}{' '}
              <b className="text-foreground">{threshold.toFixed(2)}</b>
            </p>
          </div>
          <Button variant="ghost" size="sm" onClick={onClose}>关闭</Button>
        </div>

        <div className="mb-3 flex gap-2">
          <input value={query} onChange={e => setQuery(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') run(); }}
            placeholder={isKnowledge ? '输入客户会问的事实型问题' : '输入一个客户推进场景或异议'}
            className="flex-1 rounded-[10px] border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
          <Button variant="primary" onClick={run} disabled={testing || !query.trim()}>
            {testing ? '检索中…' : '检索'}
          </Button>
        </div>

        {!result && (
          <div className="mb-4 flex flex-wrap gap-2 text-xs">
            {EXAMPLES[scope].map(q => (
              <button key={q} onClick={() => setQuery(q)}
                className="rounded-full border border-border px-3 py-1 text-muted-foreground hover:bg-surface-2 hover:text-foreground">
                {q}
              </button>
            ))}
          </div>
        )}

        {result && (
          <div className="space-y-4 text-sm">
            <div className="rounded-lg border border-border bg-surface-2 px-3 py-2 text-xs text-muted-foreground">
              本次会进入分身：{isKnowledge ? '知识' : '案例'}{' '}
              <b className={effectiveCount > 0 ? 'text-success' : 'text-warning-text'}>{effectiveCount}</b> 条
              （共 {hits.length} 条近失/命中）
            </div>

            {hits.length === 0 ? (
              <p className="text-xs leading-5 text-muted-foreground">
                连诊断下限（{isKnowledge ? '0.25' : '0.30'}）都没有命中：检查内容是否已生效、已索引、未过期，
                或换一个更接近内容标题/正文的问法。
              </p>
            ) : hits.map(h => (
              <div key={h.id} className="rounded-lg border border-border bg-surface px-3 py-2">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="rounded-full bg-surface-2 px-2 py-0.5 text-[11px] tabular-nums text-foreground">
                    {h.similarity.toFixed(2)}
                  </span>
                  <span className={`rounded-full px-2 py-0.5 text-[11px] font-medium ${
                    h.effective ? 'bg-success-bg text-success' : 'bg-warning-bg text-warning-text'
                  }`}>
                    {h.effective ? '会进入分身' : '未达阈值'}
                  </span>
                  <span className="min-w-0 flex-1 truncate text-foreground">{h.title}</span>
                </div>
                {isKnowledge
                  ? h.source && <p className="mt-1 text-[11px] text-muted-foreground">出处：{h.source}</p>
                  : h.objection && <p className="mt-1 text-[11px] text-muted-foreground">异议：{h.objection}</p>}
              </div>
            ))}

            {hits.length > 0 && effectiveCount === 0 && (
              <p className="rounded-lg bg-warning-bg px-3 py-2 text-xs leading-5 text-warning-text">
                没有{isKnowledge ? '知识' : '案例'}达到阈值：分身这次不会引用。
                {isKnowledge
                  ? '优先检查标题是否写成「场景 + 结论」，正文是否包含具体数字、条件或客户原话。'
                  : '补客户画像、场景标签和客户原话通常能提升命中。'}
              </p>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
