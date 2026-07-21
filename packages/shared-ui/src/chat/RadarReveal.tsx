'use client';

import React, { useEffect, useState } from 'react';

export interface Dimension {
  name: string;
  color: string;
  status: 'pending' | 'analyzing' | 'done';
  score?: number;
}

export interface RadarRevealProps {
  visible: boolean;
  dimensions: Dimension[];
}

export function RadarReveal({ visible, dimensions }: RadarRevealProps) {
  const [revealStep, setRevealStep] = useState(0);

  useEffect(() => {
    if (!visible) return;
    setRevealStep(0);
    const t = setInterval(() => {
      setRevealStep(s => Math.min(s + 1, dimensions.length));
    }, 1500);
    return () => clearInterval(t);
  }, [visible, dimensions.length]);

  if (!visible) return null;

  const done = revealStep >= dimensions.length;
  const cx = 40, cy = 40, r = 32;

  return (
    <div style={{
      display: 'flex', flexDirection: 'column', alignItems: 'center',
      padding: '24px 20px', gap: 14, maxWidth: 340, margin: '0 auto',
    }}>
      {/* 旋转 SVG 雷达图 */}
      <svg width="80" height="80" viewBox="0 0 80 80"
        style={{ animation: 'radarSpin 8s linear infinite' }}>
        {dimensions.map((d, i) => {
          const angle = (i / dimensions.length) * Math.PI * 2 - Math.PI / 2;
          const x = cx + r * Math.cos(angle);
          const y = cy + r * Math.sin(angle);
          return (
            <g key={d.name}>
              <line x1={cx} y1={cy} x2={x} y2={y}
                stroke={d.color} strokeWidth="1" strokeOpacity="0.3" />
              <circle cx={x} cy={y} r="3" fill={d.color} opacity={
                revealStep > i ? 1 : 0.2
              } />
            </g>
          );
        })}
        <polygon points={dimensions.map((_d, i) => {
          const angle = (i / dimensions.length) * Math.PI * 2 - Math.PI / 2;
          const rr = revealStep > i ? 28 : 4;
          return `${cx + rr * Math.cos(angle)},${cy + rr * Math.sin(angle)}`;
        }).join(' ')} fill="rgba(99,102,241,0.08)" stroke="rgba(99,102,241,0.2)" />
        <circle cx={cx} cy={cy} r="3" fill="rgba(99,102,241,0.6)" />
      </svg>

      <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--fg-high)' }}>
        {done ? '复盘完成' : '正在复盘你的对练表现'}
      </span>

      {/* 四维进度条 */}
      <div style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: 8 }}>
        {dimensions.map((d, i) => (
          <div key={d.name} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{
              width: 8, height: 8, borderRadius: '50%',
              background: revealStep > i ? d.color : 'var(--border-subtle)',
              transition: 'background 0.3s',
            }} />
            <span style={{
              fontSize: 12, color: revealStep > i ? 'var(--fg-high)' : 'var(--fg-dim)',
              flex: '0 0 80px',
            }}>{d.name}</span>
            <div style={{
              flex: 1, height: 4, borderRadius: 2,
              background: 'var(--s3)', overflow: 'hidden',
            }}>
              {revealStep > i && (
                <div style={{
                  height: '100%', borderRadius: 2,
                  width: d.score ? `${d.score}%` : '100%',
                  background: d.color,
                  animation: d.score ? undefined : 'marquee 1.5s linear infinite',
                }} />
              )}
            </div>
            <span style={{ fontSize: 11, color: 'var(--fg-dim)', flex: '0 0 36px', textAlign: 'right' }}>
              {revealStep > i ? (d.score ? `${d.score}%` : '✓') : '…'}
            </span>
          </div>
        ))}
      </div>

      <style>{`
        @keyframes radarSpin {
          from { transform: rotate(0deg); }
          to { transform: rotate(360deg); }
        }
      `}</style>
    </div>
  );
}
