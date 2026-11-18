'use client';

import React from 'react';

export interface LibraryStatItem {
  key: string;
  label: string;
  value: number;
  tone?: 'default' | 'warning' | 'success';
  active?: boolean;
  onClick?: () => void;
}

const TONE: Record<NonNullable<LibraryStatItem['tone']>, string> = {
  default: 'text-foreground',
  warning: 'text-warning-text',
  success: 'text-success',
};

/**
 * 知识库/案例库顶部的关键指标条。
 * 用「一条带分隔的数字」代替四张独立卡片，先告诉使用者现在该看什么。
 */
export function LibraryStats({ items }: { items: LibraryStatItem[] }) {
  return (
    <div className="mb-4 grid grid-cols-2 overflow-hidden rounded-xl border border-border bg-surface sm:grid-cols-4">
      {items.map((item, index) => {
        const clickable = !!item.onClick;
        return (
          <button
            key={item.key}
            type="button"
            onClick={item.onClick}
            disabled={!clickable}
            className={`relative flex flex-col items-start px-4 py-3 text-left transition-colors ${
              index > 0 ? 'border-l border-border' : ''
            } ${clickable ? 'hover:bg-surface-2' : 'cursor-default'} ${
              item.active ? 'bg-primary-light' : ''
            }`}
          >
            <span className={`text-2xl font-semibold tabular-nums ${TONE[item.tone ?? 'default']}`}>
              {item.value}
            </span>
            <span className={`mt-0.5 text-xs ${item.active ? 'text-primary' : 'text-muted-foreground'}`}>
              {item.label}
            </span>
            {item.active && <span className="absolute inset-x-0 bottom-0 h-0.5 bg-primary" />}
          </button>
        );
      })}
    </div>
  );
}
