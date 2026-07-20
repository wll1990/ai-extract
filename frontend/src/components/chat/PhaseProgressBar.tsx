'use client';

import React from 'react';
import { cn } from '@/lib/utils';

/** 阶段信息 */
interface PhaseItem {
  name: string;
  label: string;
  status: 'current' | 'completed' | 'pending';
}

/** 进度条组件 Props */
export interface PhaseProgressBarProps {
  phases: PhaseItem[];
  className?: string;
}

/**
 * 四阶段进度条组件
 *
 * 显示AI萃取访谈四个阶段的进度。
 * 已完成：实心蓝+✓，当前：金琥珀色+外发光，未开始：空心灰。
 * 阶段切换时圆点缩放动画300ms，连线填充动画400ms。
 */
export const PhaseProgressBar: React.FC<PhaseProgressBarProps> = ({
  phases,
  className,
}) => {
  return (
    <div className={cn('flex items-center justify-center gap-0 px-6 py-4', className)}>
      {phases.map((phase, index) => (
        <React.Fragment key={phase.name}>
          {/* 阶段节点 */}
          <div className="flex flex-col items-center gap-1.5">
            {/* 圆点 */}
            <div
              className={cn(
                'relative flex h-9 w-9 items-center justify-center rounded-full transition-all duration-300',
                phase.status === 'completed' && 'bg-foreground',
                phase.status === 'current' && 'bg-primary shadow-glow scale-110',
                phase.status === 'pending' && 'border-2 border-border-strong bg-transparent',
              )}
            >
              {phase.status === 'completed' && (
                <svg
                  className="h-4 w-4 text-white"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  strokeWidth={2.5}
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    d="M5 13l4 4L19 7"
                  />
                </svg>
              )}
              {phase.status === 'current' && (
                <div className="h-2.5 w-2.5 rounded-full bg-surface-2" />
              )}
            </div>
            {/* 文字 */}
            <span
              className={cn(
                'text-xs whitespace-nowrap',
                phase.status === 'completed' && 'text-foreground font-medium',
                phase.status === 'current' && 'text-primary font-semibold',
                phase.status === 'pending' && 'text-muted-foreground-2',
              )}
            >
              {phase.label}
            </span>
          </div>

          {/* 连接线 */}
          {index < phases.length - 1 && (
            <div className="relative mx-1 mb-6 h-[2px] w-16 overflow-hidden rounded-full bg-border">
              <div
                className={cn(
                  'absolute inset-0 rounded-full bg-foreground transition-all duration-400',
                  phase.status === 'completed' && phases[index + 1].status !== 'pending'
                    ? 'w-full'
                    : phase.status === 'completed' && phases[index + 1].status === 'current'
                      ? 'w-1/2'
                      : 'w-0',
                )}
              />
            </div>
          )}
        </React.Fragment>
      ))}
    </div>
  );
};
