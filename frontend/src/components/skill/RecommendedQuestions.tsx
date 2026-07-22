'use client';

import React, { useState } from 'react';

interface Props {
  questions: string[];
  onQuestionClick: (q: string) => void;
  label?: string;
  max?: number;
}

/**
 * 推荐问题按钮列表 — 首屏 max 个，可分批展开。
 */
export default function RecommendedQuestions({ questions, onQuestionClick, label, max = 4 }: Props) {
  const [batch, setBatch] = useState(1);
  if (questions.length === 0) return null;

  const visibleCount = Math.min(batch * max, questions.length);
  const visible = questions.slice(0, visibleCount);
  const hasMore = visibleCount < questions.length;
  const canCollapse = batch > 1;

  return (
    <div className="space-y-2 py-2">
      {label && <p className="text-xs text-muted-foreground text-center">{label}</p>}
      <div className="flex flex-col gap-2 max-w-[420px] mx-auto">
        {visible.map((q, i) => (
          <button key={i} type="button" onClick={() => onQuestionClick(q)}
            className="flex items-center gap-3 w-full rounded-xl border border-border bg-white px-4 py-3 text-left shadow-sm hover:border-primary/30 hover:bg-primary-light/50 transition-all">
            <span className="flex h-8 w-8 flex-none items-center justify-center rounded-full bg-[#eef2ff] text-sm">💬</span>
            <span className="flex-1 text-sm font-medium text-foreground">{q}</span>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-4 w-4 flex-none text-muted-foreground"><path d="M9 6l6 6-6 6" /></svg>
          </button>
        ))}
      </div>
      {(hasMore || canCollapse) && (
        <div className="flex justify-center gap-4 pt-1">
          {hasMore && (
            <button onClick={() => setBatch(prev => prev + 1)}
              className="text-xs text-primary hover:underline">
              展开更多 →
            </button>
          )}
          {canCollapse && (
            <button onClick={() => setBatch(1)}
              className="text-xs text-muted-foreground hover:underline">
              收起
            </button>
          )}
        </div>
      )}
    </div>
  );
}
