'use client';

import React from 'react';

export interface QuickRepliesProps {
  /** 快捷回复选项 */
  replies: string[];
  /** 点击回调，传入选中的文本 */
  onSelect: (text: string) => void;
  /** 是否禁用（流式输出中禁用） */
  disabled?: boolean;
}

/**
 * 快捷回复按钮组 — 降低销冠输入门槛。
 * AI 开场或追问后提供 2-4 个可点击选项，点击即发送。
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
