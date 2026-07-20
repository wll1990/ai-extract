'use client';

import React from 'react';

export interface ThinkingCardProps {
  /** 显示文案，默认"正在思考…" */
  text?: string;
}

/**
 * 思考中动画卡片 — AI 生成回复前的过渡反馈。
 * 由父组件在流式响应开始时插入，流式首 chunk 到达后替换为正式消息。
 */
export const ThinkingCard: React.FC<ThinkingCardProps> = ({ text = '正在思考…' }) => (
  <div className="mx-auto mb-4 max-w-[720px] animate-[fadeIn_300ms_ease-out]">
    <div className="rounded-2xl rounded-bl-md bg-primary-light px-5 py-4 shadow-sm">
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-1">
          <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-primary" style={{ animationDelay: '0ms' }} />
          <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-primary" style={{ animationDelay: '200ms' }} />
          <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-primary" style={{ animationDelay: '400ms' }} />
        </div>
        <span className="text-sm text-muted-foreground">{text}</span>
      </div>
      {/* 进度条动画 */}
      <div className="mt-3 h-1 w-full overflow-hidden rounded-full bg-border">
        <div className="h-full w-1/3 animate-[shimmer_2s_ease-in-out_infinite] rounded-full bg-primary/40" />
      </div>
    </div>
  </div>
);
