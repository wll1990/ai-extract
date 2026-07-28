'use client';

import React from 'react';

export interface StatBadgeProps {
  /** 统计数值 */
  value: number;
  /** 数值后标签，如 "次对话" */
  label: string;
  /** 前缀图标 emoji */
  icon?: string;
  /** 尺寸: sm=卡片用, md=Hero用 */
  size?: 'sm' | 'md';
  /** 仅在 value > 0 时渲染，为 0 则 return null（降级策略核心） */
  hideOnZero?: boolean;
}

const SIZE_MAP = {
  sm: { value: 13, label: 10, gap: 3 },
  md: { value: 15, label: 11, gap: 4 },
};

export function StatBadge({ value, label, icon, size = 'md', hideOnZero = true }: StatBadgeProps) {
  if (hideOnZero && value <= 0) return null;
  const s = SIZE_MAP[size];
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'baseline', gap: s.gap,
      fontSize: s.value, fontWeight: 600, color: '#1e293b',
    }}>
      {icon && <span style={{ fontSize: s.value - 1 }}>{icon}</span>}
      <span>{value.toLocaleString()}</span>
      <span style={{ fontSize: s.label, fontWeight: 400, color: '#64748b' }}>{label}</span>
    </span>
  );
}
