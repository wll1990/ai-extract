'use client';

import { useState } from 'react';
import { API_BASE } from '@/lib/api/client';

interface Props {
  questions: string[];
  skillId: string;
  onSelect: (question: string) => void;
}

/**
 * RAG 无匹配时展示的推荐问题卡片。
 *
 * 用户点击推荐问题 → 自动填入输入框 → 发起新对话。
 * 点击时上报 recommendation_click 埋点。
 * 首屏 4 个，可分批展开。
 */
export default function SkillSuggestedQuestions({ questions, skillId, onSelect }: Props) {
  const [batch, setBatch] = useState(1);
  const BATCH_SIZE = 4;

  if (!questions || questions.length === 0) return null;

  const visibleCount = Math.min(batch * BATCH_SIZE, questions.length);
  const visible = questions.slice(0, visibleCount);
  const hasMore = visibleCount < questions.length;
  const canCollapse = batch > 1;

  const handleClick = (q: string) => {
    fetch(`${API_BASE}/analytics/event`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        event_type: 'recommendation_click',
        event_data: { question: q, skill_id: skillId },
      }),
    }).catch(() => {});

    onSelect(q);
  };

  return (
    <div className="mt-4 rounded-xl border border-border bg-surface-2 p-4">
      <p className="mb-3 text-sm text-muted-foreground">
        🤔 这个问题我暂时还不太了解，要不试试问我这些？
      </p>
      <div className="flex flex-wrap gap-2">
        {visible.map((q, i) => (
          <button
            key={i}
            type="button"
            onClick={() => handleClick(q)}
            className="rounded-lg bg-primary/10 px-3 py-2 text-sm text-primary transition-colors hover:bg-primary/20"
          >
            💬 {q}
          </button>
        ))}
      </div>
      {(hasMore || canCollapse) && (
        <div className="mt-2 flex justify-center gap-4">
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
