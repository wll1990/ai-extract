'use client';

import React from 'react';

export type FlowStepStatus = 'done' | 'current' | 'todo';

export interface FlowStep {
  id: string;
  title: string;
  desc: string;
  status: FlowStepStatus;
  /** 点击整块步骤执行的跳转/筛选；不传则只读 */
  onClick?: () => void;
  /** 右下角的动作文案，如「去审核」「新建运行」 */
  actionLabel?: string;
}

/**
 * 页面流程条：只用于真实有先后顺序的工作流（录入 → 审核 → 生效 → 维护）。
 *
 * <p>序号是信息而不是装饰：它告诉使用者「现在卡在第几步、下一步点哪里」。
 * 每一步本身就是入口，避免再去一堆按钮里找对应操作。</p>
 */
export function FlowSteps({ steps, className = '', compact = false }: {
  steps: FlowStep[];
  className?: string;
  /** 列表页用紧凑横条；大卡片留给需要强调流程的页面（如评测中心） */
  compact?: boolean;
}) {
  if (compact) {
    return (
      <div className={`flex flex-wrap items-center gap-1 rounded-xl border border-border bg-surface px-3 py-2 ${className}`}>
        <span className="mr-1 text-[11px] font-medium text-muted-foreground">流程</span>
        {steps.map((step, index) => (
          <React.Fragment key={step.id}>
            {index > 0 && <span className="px-0.5 text-muted-foreground-2">›</span>}
            <button
              type="button"
              onClick={step.onClick}
              disabled={!step.onClick}
              className={`inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[12px] transition-colors ${
                step.status === 'current'
                  ? 'bg-primary-light font-medium text-primary'
                  : step.status === 'done'
                    ? 'text-success hover:bg-surface-2'
                    : 'text-muted-foreground hover:bg-surface-2'
              } ${step.onClick ? 'cursor-pointer' : 'cursor-default'}`}
            >
              <span className="text-[10px]">{step.status === 'done' ? '✓' : index + 1}</span>
              {step.title}
            </button>
          </React.Fragment>
        ))}
      </div>
    );
  }
  // Tailwind 只识别完整类名，不能用模板字符串拼 lg:grid-cols-${n}
  const colClass = steps.length >= 4 ? 'lg:grid-cols-4'
    : steps.length === 3 ? 'lg:grid-cols-3'
      : 'lg:grid-cols-2';
  return (
    <ol className={`grid gap-2 sm:grid-cols-2 ${colClass} ${className}`}>
      {steps.map((step, index) => {
        const clickable = !!step.onClick;
        return (
          <li key={step.id} className="min-w-0">
            <button
              type="button"
              onClick={step.onClick}
              disabled={!clickable}
              aria-current={step.status === 'current' ? 'step' : undefined}
              className={`group h-full w-full rounded-xl border px-3.5 py-3 text-left transition-colors ${
                step.status === 'current'
                  ? 'border-primary bg-primary-light'
                  : step.status === 'done'
                    ? 'border-border bg-surface-2 hover:border-primary'
                    : 'border-border bg-surface'
              } ${clickable ? 'cursor-pointer' : 'cursor-default'}`}
            >
              <span className="flex items-center gap-2">
                <span
                  className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-[11px] font-semibold ${
                    step.status === 'done'
                      ? 'bg-success-bg text-success'
                      : step.status === 'current'
                        ? 'bg-primary text-white'
                        : 'bg-surface-2 text-muted-foreground'
                  }`}
                >
                  {step.status === 'done' ? '✓' : index + 1}
                </span>
                <span className={`truncate text-[13px] font-semibold ${
                  step.status === 'todo' ? 'text-muted-foreground' : 'text-foreground'
                }`}>
                  {step.title}
                </span>
                {step.status === 'current' && (
                  <span className="ml-auto shrink-0 text-[10px] font-medium text-primary">当前要做</span>
                )}
              </span>
              <span className="mt-2 block text-xs leading-5 text-muted-foreground">{step.desc}</span>
              {step.actionLabel && (
                <span className="mt-2 block text-xs font-medium text-primary group-hover:underline">
                  {step.actionLabel}
                </span>
              )}
            </button>
          </li>
        );
      })}
    </ol>
  );
}
