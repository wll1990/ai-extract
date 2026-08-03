'use client';

import React, { useState } from 'react';

interface InterviewMessageBubbleProps {
  role: 'ai' | 'user';
  content: string;
  depth?: number;
  createdAt?: string;
  isNew?: boolean;
}

/** 访谈专用消息气泡，轻量实现，不依赖平台端 MessageBubble */
export function InterviewMessageBubble({ role, content, depth, createdAt, isNew }: InterviewMessageBubbleProps) {
  const isUser = role === 'user';

  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'} ${isNew ? 'animate-[fadeIn_0.3s_ease-out]' : ''}`}>
      <div className={`max-w-[85%] rounded-2xl px-4 py-3 text-sm leading-relaxed ${
        isUser
          ? 'bg-[#2147ff] text-white rounded-br-md'
          : 'bg-white border border-[#e1e7ff] text-[#10162f] rounded-bl-md shadow-sm'
      }`}>
        <div className="whitespace-pre-wrap break-words">{content}</div>
        {createdAt && (
          <div className={`mt-1 text-[10px] ${isUser ? 'text-white/60' : 'text-[#94a3b8]'}`}>
            {formatTime(createdAt)}
            {depth !== undefined && depth > 0 && <span className="ml-2">追问 {depth}</span>}
          </div>
        )}
      </div>
    </div>
  );
}

function formatTime(dateStr: string): string {
  try {
    const d = new Date(dateStr);
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  } catch {
    return '';
  }
}
