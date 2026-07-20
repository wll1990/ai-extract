'use client';

import React from 'react';

interface Props {
  questions: string[];
  onQuestionClick: (q: string) => void;
  label?: string;
  max?: number;
}

export default function RecommendedQuestions({ questions, onQuestionClick, label, max = 6 }: Props) {
  if (questions.length === 0) return null;

  return (
    <div className="space-y-2 py-2">
      {label && <p className="text-xs text-muted-foreground text-center">{label}</p>}
      <div className="flex flex-wrap justify-center gap-2">
        {questions.slice(0, max).map((q, i) => (
          <button key={i} type="button" onClick={() => onQuestionClick(q)}
            className="rounded-full border border-border px-3.5 py-1.5 text-sm text-foreground hover:border-foreground hover:bg-primary-light transition-colors min-h-[40px]">
            {q}
          </button>
        ))}
      </div>
    </div>
  );
}
