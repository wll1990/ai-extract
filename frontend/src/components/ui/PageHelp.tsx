'use client';

import React, { useState } from 'react';
import { Button } from '@/components/ui/Button';

export interface HelpStep {
  title: string;
  desc: string;
}

export interface HelpAction {
  /** 按钮上的原文，必须与页面一致 */
  label: string;
  /** 什么时候可用、点了发生什么、能不能撤销 */
  desc: string;
  tone?: 'primary' | 'neutral' | 'danger';
}

interface PageHelpProps {
  /** 抽屉标题，如「知识库使用说明」 */
  title: string;
  /** 一句话说明这个页面解决什么问题 */
  intro: string;
  /** 主要操作步骤 */
  steps: HelpStep[];
  /** 按钮级说明：页面里每个按钮都要有一条，避免「不知道这个按钮干嘛」 */
  actions?: HelpAction[];
  /** 注意事项 / 常见问题 */
  notes?: string[];
}

/**
 * 页面级使用说明抽屉。
 *
 * <p>统一入口，避免每个页面各写一套引导；空态和关键动作也可以引用同一份文案。</p>
 */
export function PageHelp({ title, intro, steps, actions, notes }: PageHelpProps) {
  const [open, setOpen] = useState(false);
  const toneClass = (tone?: HelpAction['tone']) => tone === 'primary'
    ? 'text-primary'
    : tone === 'danger' ? 'text-danger' : 'text-foreground';

  return (
    <>
      <Button variant="neutral" size="md" onClick={() => setOpen(true)} aria-label="查看使用说明">
        使用说明
      </Button>

      {open && (
        <div
          className="fixed inset-0 z-50 bg-black/40"
          onClick={() => setOpen(false)}
        >
          <aside
            className="absolute right-0 top-0 h-full w-full max-w-[440px] overflow-y-auto bg-surface p-6 shadow-xl"
            onClick={e => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-label={title}
          >
            <div className="mb-5 flex items-start justify-between gap-4">
              <h2 className="text-lg font-bold text-foreground">{title}</h2>
              <button
                onClick={() => setOpen(false)}
                className="text-sm text-muted-foreground hover:text-foreground"
              >
                关闭
              </button>
            </div>

            <p className="mb-5 text-sm leading-6 text-muted-foreground">{intro}</p>

            <ol className="space-y-4">
              {steps.map((step, idx) => (
                <li key={step.title} className="flex gap-3">
                  <span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary-light text-xs font-semibold text-primary">
                    {idx + 1}
                  </span>
                  <div>
                    <p className="text-sm font-medium text-foreground">{step.title}</p>
                    <p className="mt-0.5 text-sm leading-6 text-muted-foreground">{step.desc}</p>
                  </div>
                </li>
              ))}
            </ol>

            {actions && actions.length > 0 && (
              <div className="mt-6">
                <p className="mb-2 text-xs font-semibold text-muted-foreground">按钮说明</p>
                <dl className="space-y-2">
                  {actions.map(action => (
                    <div key={action.label} className="rounded-lg border border-border bg-surface-2 px-3 py-2">
                      <dt className={`text-sm font-medium ${toneClass(action.tone)}`}>{action.label}</dt>
                      <dd className="mt-0.5 text-xs leading-5 text-muted-foreground">{action.desc}</dd>
                    </div>
                  ))}
                </dl>
              </div>
            )}

            {notes && notes.length > 0 && (
              <div className="mt-6 rounded-xl border border-border bg-surface-2 p-4">
                <p className="mb-2 text-xs font-semibold uppercase text-muted-foreground">注意事项</p>
                <ul className="list-disc space-y-1.5 pl-4 text-sm leading-6 text-muted-foreground">
                  {notes.map(note => <li key={note}>{note}</li>)}
                </ul>
              </div>
            )}
          </aside>
        </div>
      )}
    </>
  );
}
