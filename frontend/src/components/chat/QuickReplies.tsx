'use client';

import React from 'react';

export interface QuickRepliesProps {
  replies: string[];
  onSelect: (text: string) => void;
  disabled?: boolean;
}

/**
 * 快捷回复按钮组 — 降低销冠输入门槛。
 * 注意：此组件由 @aiextract/shared-ui 统一提供，这里仅为类型声明占位。
 * 实际渲染的是 packages/shared-ui/src/chat/QuickReplies.tsx。
 */
export const QuickReplies: React.FC<QuickRepliesProps> = ({ replies, onSelect, disabled }) => {
  if (!replies || replies.length === 0) return null;

  return (
    <div className="mx-auto mb-4 flex max-w-[720px] flex-wrap gap-2">
      {replies.map((text, i) => (
        <button
          key={i}
          type="button"
          disabled={disabled}
          onClick={() => onSelect(text)}
          className="rounded-full border border-border px-4 py-2 text-sm text-foreground transition-colors hover:border-foreground hover:bg-primary-light disabled:opacity-40 min-h-[40px]"
        >
          {text}
        </button>
      ))}
    </div>
  );
};
