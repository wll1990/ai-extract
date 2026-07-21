'use client';

import React, { useEffect, useState } from 'react';

export interface WaveThinkingProps {
  name: string;
  visible: boolean;
  texts?: string[];
}

const DEFAULT_TEXTS = ['正在分析场景…', '正在检索经验…', '正在组织语言…'];

export function WaveThinking({ name, visible, texts = DEFAULT_TEXTS }: WaveThinkingProps) {
  const [textIdx, setTextIdx] = useState(0);

  useEffect(() => {
    if (!visible) return;
    const t = setInterval(() => setTextIdx(i => (i + 1) % texts.length), 2000);
    return () => clearInterval(t);
  }, [visible, texts.length]);

  if (!visible) return null;

  return (
    <div style={{
      display: 'flex', flexDirection: 'column', alignItems: 'center',
      padding: '32px 16px', gap: 16,
    }}>
      {/* Wi-Fi 信号弧线 */}
      <div style={{ position: 'relative', width: 48, height: 48 }}>
        {[0, 1, 2, 3].map(i => (
          <div key={i} style={{
            position: 'absolute',
            bottom: 0, left: '50%', transform: 'translateX(-50%)',
            width: 12 + i * 12, height: 12 + i * 12,
            borderRadius: '50%',
            border: '2px solid transparent',
            borderTopColor: 'var(--tangerine, #ff5c00)',
            borderRightColor: 'var(--tangerine, #ff5c00)',
            opacity: 0.15 + i * 0.25,
            animation: `waveSignal 1.5s ease-in-out ${i * 0.3}s infinite`,
          }} />
        ))}
      </div>

      <span style={{ fontSize: 13, color: 'var(--fg-mid)', fontWeight: 500 }}>
        {name}正在理解你的问题…
      </span>

      {/* 跑马灯进度条 */}
      <div style={{ width: 200, overflow: 'hidden' }}>
        <div style={{
          width: '40%', height: 3, borderRadius: 2,
          background: 'linear-gradient(90deg, #6366f1, #8b5cf6, #ec4899)',
          animation: 'marquee 1.8s linear infinite',
        }} />
        <div style={{
          textAlign: 'center', marginTop: 6,
          fontSize: 11, color: 'var(--fg-dim)',
          transition: 'opacity 0.3s',
        }}>
          {texts[textIdx]}
        </div>
      </div>

      <style>{`
        @keyframes waveSignal {
          0%, 100% { opacity: 0.15; }
          50% { opacity: 0.4; }
        }
        @keyframes marquee {
          0% { transform: translateX(-60%); }
          100% { transform: translateX(260%); }
        }
      `}</style>
    </div>
  );
}
