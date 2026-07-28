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
    <div className="mx-auto mb-4 max-w-[720px] rounded-xl px-4 py-3"
      style={{ background: '#f0f3ff', borderLeft: '3px solid #2147ff' }}>
      <p className="text-xs text-[#747f9e] mb-2 font-medium">💬 试试这些问题</p>
      <div className="flex flex-col gap-2">
        {replies.map((text, i) => (
          <button
            key={i}
            type="button"
            disabled={disabled}
            onClick={() => onSelect(text)}
            className="rounded-lg bg-white border border-[#dfe6ff] px-4 py-2.5 text-sm text-[#10162f] text-left transition-colors hover:border-[#2147ff] hover:bg-[#eef2ff] disabled:opacity-40"
          >
            {text}
          </button>
        ))}
      </div>
    </div>
  );
};
