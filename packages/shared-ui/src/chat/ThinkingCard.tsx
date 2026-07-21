'use client';

import React from 'react';

export interface ThinkingCardProps {
  /** 分身名称，默认"分身" */
  name?: string;
  /** 自定义文案，优先级高于 name */
  text?: string;
  /** 是否可见，首 chunk 到达后置 false 隐藏 */
  visible?: boolean;
}

/**
 * 思考中动画卡片 — AI 生成回复前的过渡反馈。
 * 三个跳动点 + 顶部彩色跑马灯进度条。
 */
export const ThinkingCard: React.FC<ThinkingCardProps> = ({
  name = '分身',
  text,
  visible = true,
}) => {
  if (!visible) return null;

  const label = text || `${name}正在思考…`;

  return (
    <div className="mx-auto mb-4 max-w-[720px] animate-[fadeIn_300ms_ease-out]">
      {/* 彩色跑马灯进度条 */}
      <div className="mb-3 h-0.5 w-full overflow-hidden rounded-full bg-gray-100">
        <div className="h-full w-1/2 animate-[marquee_1.8s_linear_infinite] rounded-full bg-gradient-to-r from-indigo-400 via-purple-500 to-pink-400" />
      </div>

      <div className="rounded-2xl rounded-bl-md bg-primary-light px-5 py-4 shadow-sm">
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1">
            <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-primary" style={{ animationDelay: '0ms' }} />
            <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-primary" style={{ animationDelay: '200ms' }} />
            <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-primary" style={{ animationDelay: '400ms' }} />
          </div>
          <span className="text-sm text-muted-foreground">{label}</span>
        </div>
      </div>
    </div>
  );
};
