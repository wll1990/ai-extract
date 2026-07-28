'use client';

import React from 'react';
import { cn } from '@/lib/utils';
import { PHASE_LABELS } from '@/lib/constants';
import { ChatAvatar } from '@aiextract/shared-ui';

/** 消息气泡 Props */
export interface MessageBubbleProps {
  role: 'ai' | 'user' | 'system';
  content: string;
  depth?: number;
  phase?: string;
  createdAt?: string;
  isNew?: boolean;
  /** 卡片 variant（新增，默认=普通气泡） */
  variant?: 'default' | 'question-card' | 'clue-card';
  /** question-card 的副标题（variant='question-card' 时展示） */
  questionKicker?: string;
  /** question-card 的提示文字 */
  questionHint?: string;
  /** clue-card 的标签列表 */
  clueTags?: string[];
  /** clue-card 的标题 */
  clueTitle?: string;
}

/**
 * 对话气泡组件
 *
 * 支持三种样式：
 * - AI消息：灰底#EEF0F4左对齐，圆角16px左下直角
 * - AI追问：左侧2px金色竖线，缩进16px
 * - 用户消息：白底右对齐，圆角16px右下直角
 * - 新消息淡入动画200ms
 */
export const MessageBubble: React.FC<MessageBubbleProps> = ({
  role,
  content,
  depth = 0,
  phase,
  createdAt,
  isNew = false,
  variant = 'default',
  questionKicker,
  questionHint,
  clueTags,
  clueTitle,
}) => {
  const isAi = role === 'ai';
  const isUser = role === 'user';
  const isDeep = depth > 0;

  if (role === 'system') return null;

  const indent = Math.min(depth, 3) * 16;

  // question-card: AI 问题用特殊卡片样式
  if (variant === 'question-card') {
    return (
      <div className={cn('flex w-full justify-start', isNew && 'animate-[fadeIn_200ms_ease-out]')}>
        <div className="max-w-[85%] rounded-2xl rounded-bl-md border border-primary/20 bg-primary-light/60 px-5 py-4 shadow-sm">
          {questionKicker && <div className="mb-1 text-xs font-medium text-primary">{questionKicker}</div>}
          <p className="text-[15px] font-semibold text-foreground leading-relaxed">{content}</p>
          {questionHint && <p className="mt-2 text-xs text-muted-foreground leading-relaxed">{questionHint}</p>}
          {createdAt && <div className="mt-2 text-right text-[11px] text-muted-foreground-2">{formatTime(createdAt)}</div>}
        </div>
      </div>
    );
  }

  // clue-card: 线索/价值标签卡片
  if (variant === 'clue-card') {
    return (
      <div className={cn('flex w-full justify-start', isNew && 'animate-[fadeIn_200ms_ease-out]')}>
        <div className="max-w-[85%] rounded-2xl rounded-bl-md border border-amber-200 bg-amber-50/60 px-5 py-4 shadow-sm">
          {clueTitle && <div className="mb-2 text-sm font-semibold text-foreground">{clueTitle}</div>}
          {clueTags && clueTags.length > 0 && (
            <div className="flex flex-wrap gap-1.5 mb-2">
              {clueTags.map((tag, i) => (
                <span key={i} className="rounded-full bg-primary-light px-3 py-1 text-xs font-medium text-primary">{tag}</span>
              ))}
            </div>
          )}
          {content && <p className="text-sm text-foreground leading-relaxed">{content}</p>}
          {createdAt && <div className="mt-2 text-right text-[11px] text-muted-foreground-2">{formatTime(createdAt)}</div>}
        </div>
      </div>
    );
  }

  return (
    <div
      className={cn(
        'flex w-full items-start gap-2',
        isUser ? 'justify-end' : 'justify-start',
        isNew && 'animate-[fadeIn_200ms_ease-out]',
      )}
    >
      {/* AI 头像 */}
      {isAi && <ChatAvatar role="ai" size={28} />}

      <div
        className={cn(
          'relative max-w-[80%]',
          isDeep && 'ml-4',
        )}
        style={{ marginLeft: isUser ? undefined : `${indent}px` }}
      >
        {/* AI追问左侧金色竖线 */}
        {isDeep && (
          <div className="absolute -left-4 top-0 bottom-0 w-[2px] rounded-full bg-primary" />
        )}

        {/* 气泡主体 */}
        <div
          className={cn(
            'px-4 py-3 text-sm leading-relaxed shadow-sm',
            isUser && 'rounded-2xl rounded-br-md bg-surface-2 text-foreground shadow-sm',
            isAi && !isDeep && 'rounded-2xl rounded-bl-md bg-primary-light text-foreground',
            isAi && isDeep && 'rounded-2xl rounded-bl-md bg-primary-light text-foreground',
          )}
        >
          {/* 阶段标签 */}
          {phase && (
            <div className="mb-1 text-[11px] text-muted-foreground font-medium uppercase tracking-wide">
              {phaseLabel(phase)}
            </div>
          )}

          {/* 内容 */}
          <p className="whitespace-pre-wrap break-words">{content}</p>

          {/* 时间戳 */}
          {createdAt && (
            <div className="mt-1.5 text-right text-[11px] text-muted-foreground-2">
              {formatTime(createdAt)}
            </div>
          )}
        </div>
      </div>

      {/* 用户头像 */}
      {isUser && <ChatAvatar role="user" size={28} />}
    </div>
  );
};

/**
 * 阶段映射为中文标签
 */
function phaseLabel(phase: string): string {
  return PHASE_LABELS[phase] || phase;
}

/**
 * 格式化时间显示
 */
function formatTime(dateStr: string): string {
  try {
    const date = new Date(dateStr);
    return date.toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return '';
  }
}
