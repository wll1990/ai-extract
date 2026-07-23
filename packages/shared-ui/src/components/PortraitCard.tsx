'use client';

import React, { useState } from 'react';

/* ── 星星浮动 keyframes ── */
const STYLE_ID = 'portrait-card-styles';
if (typeof document !== 'undefined' && !document.getElementById(STYLE_ID)) {
  const s = document.createElement('style');
  s.id = STYLE_ID;
  s.textContent = `
    @keyframes spark-float {
      0%, 100% { transform: translateY(0) rotate(0deg); }
      50%      { transform: translateY(-7px) rotate(8deg); }
    }
  `;
  document.head.appendChild(s);
}

const EGG: React.CSSProperties = {
  borderRadius: '48% 48% 42% 42% / 54% 54% 38% 38%',
  filter: 'drop-shadow(0 15px 22px rgba(36,68,185,.15))',
};

interface PortraitCardProps {
  src?: string;
  alt?: string;
}

export function PortraitCard({ src, alt }: PortraitCardProps) {
  const [imgError, setImgError] = useState(false);
  // 优先用自定义 src，失败或没有时用 def-avatar.png
  const imgSrc = (src && !imgError) ? src : '/def-avatar.png';

  return (
    <div style={{
      position: 'relative',
      width: '100%',
      maxWidth: 220,
      height: 0,
      paddingBottom: '81.8%', /* 180/220，撑出高度 */
      margin: '0 auto',
    }}>
      {/* ✦ 装饰星星 */}
      <span style={{
        position: 'absolute', top: '10%', left: '5%', zIndex: 2, pointerEvents: 'none',
        fontSize: 17, color: '#2147ff',
        animation: 'spark-float 3.4s ease-in-out infinite',
      }}>✦</span>
      <span style={{
        position: 'absolute', top: '12%', right: '4%', zIndex: 2, pointerEvents: 'none',
        fontSize: 17, color: '#2147ff',
        animation: 'spark-float 3.4s ease-in-out infinite', animationDelay: '0.8s',
      }}>✦</span>
      <span style={{
        position: 'absolute', bottom: '11%', right: '8%', zIndex: 2, pointerEvents: 'none',
        fontSize: 17, color: '#ff4d5f',
        animation: 'spark-float 3.4s ease-in-out infinite', animationDelay: '1.4s',
      }}>✦</span>

      {/* 头像图片 */}
      <img
        src={imgSrc}
        alt={alt || ''}
        onError={() => { if (src && !imgError) setImgError(true); }}
        style={{
          position: 'absolute',
          inset: 0,
          width: '100%',
          height: '100%',
          objectFit: 'cover',
          ...EGG,
        }}
      />
    </div>
  );
}
