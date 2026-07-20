'use client';

import React from 'react';

export interface ChatComposerProps {
  /** 输入值 */
  value: string;
  /** 输入变更回调 */
  onChange: (value: string) => void;
  /** 发送回调 */
  onSend: () => void;
  /** 是否禁用 */
  disabled?: boolean;
  /** placeholder */
  placeholder?: string;
  /** 是否显示正在输入指示器 */
  isStreaming?: boolean;
  /** 底部 footer 文案（可选） */
  footerNote?: string;
  /** 左侧工具按钮（语音等），可选 */
  leftTools?: React.ReactNode;
  /** 发消息按键回调（Enter发送） */
  onKeyDown?: (e: React.KeyboardEvent) => void;
}

/**
 * 统一底部输入区 — textarea + 发送按钮 + footer + 可选工具区。
 * 替换四种聊天模式各自手写的输入区。
 */
export const ChatComposer: React.FC<ChatComposerProps> = ({
  value, onChange, onSend, disabled, placeholder, isStreaming,
  footerNote, leftTools, onKeyDown,
}) => {
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (onKeyDown) { onKeyDown(e); return; }
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); onSend(); }
  };

  return (
    <div className="sticky bottom-0 border-t border-border bg-surface-2 px-4 sm:px-6 py-3">
      <div className="mx-auto flex max-w-[720px] items-end gap-3">
        {/* 左侧工具（如 VoiceInput） */}
        {leftTools}

        <textarea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={placeholder || '输入你的回答…'}
          disabled={disabled}
          rows={1}
          className="flex-1 resize-none rounded-xl border border-border bg-surface-2 px-4 py-3 text-sm text-foreground placeholder-muted-foreground-2 outline-none transition-all focus:border-foreground focus:ring-1 focus:ring-foreground/20 disabled:opacity-50"
          style={{ minHeight: '52px', maxHeight: '120px' }}
          onInput={(e) => {
            const t = e.target as HTMLTextAreaElement;
            t.style.height = 'auto';
            t.style.height = `${Math.min(t.scrollHeight, 120)}px`;
          }}
        />

        {/* 发送按钮 */}
        <button
          type="button"
          onClick={onSend}
          disabled={!value.trim() || disabled}
          className="flex h-[52px] w-[52px] flex-shrink-0 items-center justify-center rounded-xl bg-foreground text-white transition-all hover:bg-primary disabled:cursor-not-allowed disabled:opacity-40"
          aria-label="发送消息"
        >
          {isStreaming ? (
            <div className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" />
          ) : (
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 12L3.269 3.126A59.768 59.768 0 0121.485 12 59.77 59.77 0 013.27 20.876L5.999 12zm0 0h7.5" />
            </svg>
          )}
        </button>
      </div>

      {/* Footer */}
      {footerNote && (
        <p className="mt-2 text-center text-xs text-muted-foreground-2">{footerNote}</p>
      )}
    </div>
  );
};
