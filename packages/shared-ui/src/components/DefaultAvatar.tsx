'use client';

import React from 'react';

/**
 * DefaultAvatar — 默认头像，带装饰光环 + 旋转渐变描边 + 微光点 + 呼吸辉光。
 *
 * 三端统一：头像区域无自定义图片时使用此组件，替代静态占位图或文字首字母。
 */

/* ── CSS keyframes ── */
const STYLE_ID = 'default-avatar-keyframes';
const KEYFRAMES = `
@keyframes av-rotate {
  0%   { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}
@keyframes av-shimmer {
  0%   { transform: translateX(-100%) rotate(25deg); }
  100% { transform: translateX(200%) rotate(25deg); }
}
@keyframes av-glow-pulse {
  0%, 100% { opacity: 0.25; }
  50%      { opacity: 0.55; }
}
@keyframes av-float-1 {
  0%, 100% { transform: translate(0, 0) scale(1); opacity: 0.6; }
  50%      { transform: translate(2px, -3px) scale(1.3); opacity: 1; }
}
@keyframes av-float-2 {
  0%, 100% { transform: translate(0, 0) scale(1); opacity: 0.5; }
  50%      { transform: translate(-2px, 3px) scale(1.2); opacity: 0.9; }
}
@keyframes av-float-3 {
  0%, 100% { transform: translate(0, 0) scale(1); opacity: 0.7; }
  50%      { transform: translate(3px, 2px) scale(1.1); opacity: 1; }
}
`;

if (typeof document !== 'undefined' && !document.getElementById(STYLE_ID)) {
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = KEYFRAMES;
  document.head.appendChild(style);
}

/* ── 人物剪影 ── */
function Silhouette() {
  return (
    <svg viewBox="0 0 24 24" fill="none" style={{ width: '42%', height: '42%', position: 'relative', zIndex: 4 }}>
      <circle cx="12" cy="7.5" r="3.2" fill="rgba(255,255,255,0.9)" />
      <path d="M4.5 21c0-4.5 3.4-7.5 7.5-7.5s7.5 3 7.5 7.5" fill="rgba(255,255,255,0.9)" />
    </svg>
  );
}

/* ── 装饰光点 ── */
const SPARKLES = [
  { top: '10%', left: '82%', size: 5, delay: '0s', anim: 'av-float-1' },
  { top: '78%', left: '8%',  size: 4, delay: '0.6s', anim: 'av-float-2' },
  { top: '12%', left: '14%', size: 3, delay: '1.2s', anim: 'av-float-3' },
  { top: '80%', left: '84%', size: 4, delay: '0.3s', anim: 'av-float-1' },
];

export function DefaultAvatar() {
  return (
    <div
      style={{
        width: '100%',
        height: '100%',
        borderRadius: 'inherit',
        position: 'relative',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      {/* ═══ 外层：旋转渐变光环 ═══ */}
      <div
        style={{
          position: 'absolute',
          inset: -6,
          borderRadius: 'inherit',
          background: 'conic-gradient(from 0deg, #818cf8, #c084fc, #60a5fa, #818cf8)',
          opacity: 0.3,
          animation: 'av-rotate 6s linear infinite',
          filter: 'blur(6px)',
        }}
      />

      {/* ═══ 中层：柔光晕 ═══ */}
      <div
        style={{
          position: 'absolute',
          inset: -2,
          borderRadius: 'inherit',
          background: 'radial-gradient(circle at 30% 30%, rgba(129,140,248,0.4), transparent 70%)',
          animation: 'av-glow-pulse 3s ease-in-out infinite',
        }}
      />

      {/* ═══ 内层：主体 ── 毛玻璃质感渐变底 ── */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          borderRadius: 'inherit',
          background: 'linear-gradient(145deg, #c7d2fe 0%, #a5b4fc 35%, #818cf8 65%, #8b5cf6 100%)',
          overflow: 'hidden',
        }}
      >
        {/* 微光扫过 */}
        <div
          style={{
            position: 'absolute',
            inset: 0,
            pointerEvents: 'none',
            background:
              'linear-gradient(105deg, transparent 40%, rgba(255,255,255,0.55) 46%, rgba(255,255,255,0.75) 50%, rgba(255,255,255,0.55) 54%, transparent 60%)',
            animation: 'av-shimmer 3.5s ease-in-out infinite',
            zIndex: 1,
          }}
        />
        {/* 内发光 */}
        <div
          style={{
            position: 'absolute',
            inset: 0,
            borderRadius: 'inherit',
            background: 'radial-gradient(circle at 35% 30%, rgba(255,255,255,0.5) 0%, transparent 50%)',
            zIndex: 2,
          }}
        />
      </div>

      {/* ═══ 装饰光点 ═══ */}
      {SPARKLES.map((s, i) => (
        <div
          key={i}
          style={{
            position: 'absolute',
            top: s.top,
            left: s.left,
            width: s.size,
            height: s.size,
            borderRadius: '50%',
            background: 'white',
            boxShadow: `0 0 ${s.size * 2}px rgba(129,140,248,0.7)`,
            zIndex: 5,
            pointerEvents: 'none',
            animation: `${s.anim} 3s ease-in-out infinite`,
            animationDelay: s.delay,
          }}
        />
      ))}

      {/* ═══ 细描边（静态） ═══ */}
      <div
        style={{
          position: 'absolute',
          inset: 0,
          borderRadius: 'inherit',
          border: '1px solid rgba(129,140,248,0.2)',
          zIndex: 3,
          pointerEvents: 'none',
        }}
      />

      {/* ═══ 人物剪影 ═══ */}
      <Silhouette />
    </div>
  );
}
