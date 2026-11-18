'use client';

import React from 'react';

export interface ActionLegendItem {
  /** 按钮上的原文，必须与页面一致 */
  label: string;
  /** 点了会发生什么、有什么前提、能不能撤销 */
  desc: string;
  tone?: 'primary' | 'neutral' | 'danger';
}

/**
 * 销售侧页面的按钮说明。
 *
 * <p>销售同学不一定熟悉「复盘 / 线索 / 任务」之间的数据关系，
 * 只放一排按钮会变成猜谜。这里把每个按钮的作用、前提和结果写在按钮下方，
 * 和「使用说明」抽屉里的按钮说明保持同一份口径。</p>
 */
export function ActionLegend({
  items,
  title = '按钮说明',
  className = '',
}: {
  items: ActionLegendItem[];
  title?: string;
  className?: string;
}) {
  if (items.length === 0) return null;
  const toneClass = (tone?: ActionLegendItem['tone']) => tone === 'primary'
    ? 'text-primary'
    : tone === 'danger' ? 'text-danger' : 'text-foreground';

  return (
    <section className={`rounded-xl border border-border bg-surface-2 px-4 py-3 ${className}`}>
      <h2 className="mb-2 text-[11px] font-semibold text-muted-foreground">{title}</h2>
      <dl className="grid gap-x-6 gap-y-2 sm:grid-cols-2 xl:grid-cols-3">
        {items.map(item => (
          <div key={item.label} className="min-w-0">
            <dt className={`text-xs font-semibold ${toneClass(item.tone)}`}>{item.label}</dt>
            <dd className="mt-0.5 text-[11px] leading-5 text-muted-foreground">{item.desc}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
