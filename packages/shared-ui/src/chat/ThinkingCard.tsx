'use client';

import React from 'react';

/* ── 音波柱 keyframes（对齐 preview.html @keyframes bars）── */
const STYLE_ID = 'thinking-card-bars';
if (typeof document !== 'undefined' && !document.getElementById(STYLE_ID)) {
  const s = document.createElement('style');
  s.id = STYLE_ID;
  s.textContent = `
    @keyframes thinking-bars {
      50% { height: 5px; opacity: 0.45; }
    }
    @keyframes thinking-slide {
      0%   { transform: translateX(-100%); }
      100% { transform: translateX(290%); }
    }
  `;
  document.head.appendChild(s);
}

export interface ThinkingCardProps {
  /** 分身名称，默认"萃萃" */
  name?: string;
  /** 自定义思考文案，优先级高于 name */
  text?: string;
  /** 是否可见 */
  visible?: boolean;
}

/**
 * 思考中动画卡片 — 对齐 preview.html "萃萃正在思考"。
 * 音波柱 ▏▎▋▊ + 渐变色跑马灯进度条 + 思考文案。
 */
export const ThinkingCard: React.FC<ThinkingCardProps> = ({
  name = '萃萃',
  text,
  visible = true,
}) => {
  if (!visible) return null;

  const displayName = name || '萃萃';
  const title = `${displayName}正在思考`;
  const copy = text || '正在尊重你的原话，整理表达，并发现可能被忽略的价值线索……';

  return (
    <div className="mx-auto mb-4 w-full max-w-[720px]">

      {/* 音波柱 + 标题行 */}
      <div className="mb-2 flex items-center gap-3 pl-1"
        style={{ color: '#2147ff', fontWeight: 800, fontSize: 14 }}>
        <span>{title}</span>
        <span
          className="inline-flex items-end gap-[3px]"
          style={{ height: 17 }}
        >
          <span style={{
            width: 3, borderRadius: 4, background: '#2147ff',
            height: 7, display: 'inline-block',
            animation: 'thinking-bars 1s ease-in-out infinite',
          }} />
          <span style={{
            width: 3, borderRadius: 4, background: '#2147ff',
            height: 13, display: 'inline-block',
            animation: 'thinking-bars 1s ease-in-out infinite',
            animationDelay: '0.15s',
          }} />
          <span style={{
            width: 3, borderRadius: 4, background: '#2147ff',
            height: 17, display: 'inline-block',
            animation: 'thinking-bars 1s ease-in-out infinite',
            animationDelay: '0.3s',
          }} />
          <span style={{
            width: 3, borderRadius: 4, background: '#2147ff',
            height: 10, display: 'inline-block',
            animation: 'thinking-bars 1s ease-in-out infinite',
            animationDelay: '0.45s',
          }} />
        </span>
      </div>

      {/* 思考文案 */}
      <div className="mb-2 pl-1"
        style={{ fontSize: 12.5, color: '#65708d', lineHeight: 1.6 }}>
        {copy}
      </div>

      {/* 渐变色跑马灯进度条（对齐 preview.html @keyframes slide） */}
      <div style={{
        height: 3, background: '#e8ecff', borderRadius: 99,
        overflow: 'hidden', width: '100%',
      }}>
        <span style={{
          display: 'block', width: '46%', height: '100%', borderRadius: 99,
          background: 'linear-gradient(90deg, #2147ff, #946cff, #ff4d5f)',
          animation: 'thinking-slide 1.5s ease-in-out infinite',
        }} />
      </div>
    </div>
  );
};
