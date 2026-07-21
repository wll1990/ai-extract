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
      <div className="flex flex-wrap justify-center gap-2">
        {visible.map((q, i) => (
          <button key={i} type="button" onClick={() => onQuestionClick(q)}
            className="rounded-full border border-border px-3.5 py-1.5 text-sm text-foreground hover:border-foreground hover:bg-primary-light transition-colors min-h-[40px]">
            {q}
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
