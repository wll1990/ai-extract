'use client';

import React, { useRef, useEffect } from 'react';
import { ThinkingCard } from '@/components/chat/ThinkingCard';

export interface SkillChatViewProps {
  /** 消息渲染区域（各页面自定义气泡样式） */
  children: React.ReactNode;
  /** 输入框值 */
  inputValue: string;
  /** 输入框变更 */
  onInputChange: (value: string) => void;
  /** 发送消息 */
  onSend: () => void;
  /** 是否正在流式输出 */
  isStreaming?: boolean;
  /** 输入框 placeholder */
  placeholder?: string;
  /** 是否禁用输入 */
  disabled?: boolean;
  /** 底部自定义区域（如语音按钮、提示文字） */
  footer?: React.ReactNode;
}

/**
 * 共享聊天视图 — 消息列表 + 输入框
 *
 * 三处页面（技能聊天页、ProductDemoModal、PracticeScenarioModal）共用。
 * 消息气泡样式由各页面通过 children 自定义。
 */
export function SkillChatView({
  children,
  inputValue,
  onInputChange,
  onSend,
  isStreaming = false,
  placeholder = '输入你的问题...',
  disabled = false,
  footer,
}: SkillChatViewProps) {
  const chatEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [children]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      onSend();
    }
  };

  return (
    <div className="flex-1 flex flex-col min-h-0">
      {/* 消息列表 */}
      <div className="flex-1 overflow-y-auto px-6 py-4 space-y-3">
        {children}
        {isStreaming && <ThinkingCard text="正在思考…" />}
        <div ref={chatEndRef} />
      </div>

      {/* 输入区 */}
      {!disabled && (
        <div className="border-t border-border bg-surface-2 px-6 py-4">
          <div className="mx-auto flex max-w-[720px] items-end gap-3">
            <textarea
              value={inputValue}
              onChange={(e) => onInputChange(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={placeholder}
              disabled={isStreaming}
              rows={1}
              className="flex-1 resize-none rounded-xl border border-border bg-surface-2 px-4 py-3 text-sm text-foreground placeholder:text-muted-foreground-2 outline-none transition-all focus:border-foreground focus:ring-1 focus:ring-foreground/20 disabled:opacity-50"
              style={{ minHeight: '52px', maxHeight: '120px' }}
              onInput={(e) => {
                const t = e.target as HTMLTextAreaElement;
                t.style.height = 'auto';
                t.style.height = Math.min(t.scrollHeight, 120) + 'px';
              }}
            />
            <button
              type="button"
              onClick={onSend}
              disabled={!inputValue.trim() || isStreaming}
              className="flex h-[52px] w-[52px] flex-shrink-0 items-center justify-center rounded-xl bg-foreground text-white transition-all hover:bg-primary disabled:cursor-not-allowed disabled:opacity-40"
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
          {footer && <div className="mx-auto max-w-[720px] mt-2">{footer}</div>}
        </div>
      )}
    </div>
  );
}
