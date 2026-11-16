'use client';

import React from 'react';
import type { TourChapter, TourStep } from '@/lib/tour/types';

interface Props {
  chapter: TourChapter;
  step: TourStep;
  stepIndex: number;
  chapterIndex: number;
  chapterCount: number;
  style?: React.CSSProperties;
  cardRef?: React.Ref<HTMLDivElement>;
  missing?: boolean;
  paused?: string | null;
  modal?: boolean;
  onNext: () => void;
  onPrev: () => void;
  onClose: () => void;
  onMinimize: () => void;
  onRetry: () => void;
  onResume: () => void;
  isLast: boolean;
}

export function TourCard({
  chapter, step, stepIndex, chapterIndex, chapterCount, style, cardRef,
  missing, paused, modal, onNext, onPrev, onClose, onMinimize, onRetry, onResume, isLast,
}: Props) {
  const total = chapterCount;
  const current = chapterIndex + 1;

  return (
    <div
      ref={cardRef}
      role="dialog"
      aria-modal={modal ? 'true' : undefined}
      aria-labelledby={`tour-title-${step.id}`}
      tabIndex={-1}
      className="pointer-events-auto fixed z-[201] w-[360px] max-w-[calc(100vw-24px)] rounded-xl border border-border bg-surface-2 p-4 shadow-2xl outline-none"
      style={style}
      data-tour-card
    >
      <div className="mb-2 flex items-center justify-between gap-2">
        <span className="text-[11px] text-muted-foreground">
          {current}/{total} · {chapter.title}
        </span>
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={onMinimize}
            className="rounded px-1.5 py-0.5 text-[11px] text-muted-foreground hover:bg-surface"
            title="最小化"
            aria-label="最小化导览"
          >
            —
          </button>
          <button
            type="button"
            onClick={onClose}
            className="rounded px-1.5 py-0.5 text-[11px] text-muted-foreground hover:bg-surface"
            aria-label="关闭导览"
          >
            ✕
          </button>
        </div>
      </div>

      <h3 id={`tour-title-${step.id}`} className="mb-1.5 text-sm font-semibold text-foreground">
        {step.title}
      </h3>
      <p className="text-[13px] leading-relaxed text-muted-foreground">{step.body}</p>

      {step.bullets && step.bullets.length > 0 && (
        <ul className="mt-2 space-y-1">
          {step.bullets.map(b => (
            <li key={b} className="flex gap-1.5 text-[12px] text-foreground">
              <span className="text-primary">•</span>
              <span>{b}</span>
            </li>
          ))}
        </ul>
      )}

      {missing && (
        <p className="mt-2 rounded bg-amber-50 px-2 py-1.5 text-[12px] text-amber-700">
          这一步在当前页面没找到，可能是演示数据为空。可以重试，或直接跳过。
        </p>
      )}
      {paused && (
        <p className="mt-2 rounded bg-amber-50 px-2 py-1.5 text-[12px] text-amber-700">
          {paused}。继续会回到当前步骤。
        </p>
      )}

      <div className="mt-3 h-1 w-full overflow-hidden rounded-full bg-border" role="progressbar"
        aria-label="章节进度" aria-valuemin={1} aria-valuemax={total} aria-valuenow={current}
        aria-valuetext={`第 ${current} 章，共 ${total} 章`}>
        <div className="h-full bg-primary transition-[width] duration-200"
          style={{ width: `${(current / total) * 100}%` }} />
      </div>

      <div className="mt-2 text-right">
        <a href="/help" target="_blank" rel="noreferrer"
          className="text-[11px] text-muted-foreground hover:text-primary hover:underline">
          查看图文步骤 →
        </a>
      </div>

      <div className="mt-3 flex items-center justify-between gap-2">
        <div className="flex gap-1.5">
          <button
            type="button"
            onClick={onPrev}
            disabled={current === 1 && stepIndex === 0}
            className="rounded border border-border px-2.5 py-1 text-[12px] text-muted-foreground disabled:opacity-40"
          >
            上一步
          </button>
          {missing && (
            <>
              <button
                type="button"
                onClick={onRetry}
                className="rounded border border-border px-2.5 py-1 text-[12px] text-foreground"
              >
                重试
              </button>
              <button
                type="button"
                onClick={onNext}
                className="rounded border border-border px-2.5 py-1 text-[12px] text-muted-foreground"
              >
                跳过此步
              </button>
            </>
          )}
          {paused && (
            <button
              type="button"
              onClick={onResume}
              className="rounded border border-border px-2.5 py-1 text-[12px] text-foreground"
            >
              继续
            </button>
          )}
        </div>
        <button
          type="button"
          onClick={onNext}
          className="rounded bg-primary px-3 py-1.5 text-[12px] font-medium text-white"
        >
          {isLast ? '完成' : '下一步 →'}
        </button>
      </div>
    </div>
  );
}
