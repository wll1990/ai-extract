'use client';

import React from 'react';

/* ── 信任条数据 ── */
const TRAITS = [
  { icon: '♡', title: '真实案例', desc: '销冠真实对话与文档提炼' },
  { icon: '✦', title: '溯源可查', desc: '每句回答有据可依' },
  { icon: '▤', title: '即学即用', desc: '30秒拿到可执行话术' },
];

const ICON_COLORS = ['#ff4d5f', '#2147ff', '#8b5cf6'];

/**
 * TrustBadge — 品牌信任条，三列横排（左图标+右文字），flex-nowrap 绝不折行。
 */
export function TrustBadge() {
  return (
    <div
      style={{
        width: 'var(--trust-width, 100%)',
        padding: '14px 14px 8px',
        background: 'var(--trust-bg, transparent)',
        borderTop: '1px solid var(--trust-divider, #edf0fb)',
      }}
    >
      {/* 三列信任条 — flex-nowrap */}
      <div
        style={{
          display: 'flex',
          flexWrap: 'nowrap',
          gap: 8,
          width: '100%',
        }}
      >
        {TRAITS.map((t, i) => (
          <div
            key={i}
            style={{
              display: 'flex',
              flex: 1,
              gap: 6,
              alignItems: 'center',
              padding: '2px 2px',
              minWidth: 0,
            }}
          >
            {/* 图标圆 */}
            <div
              style={{
                width: 26,
                height: 26,
                borderRadius: '50%',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                background: '#f3f6ff',
                color: ICON_COLORS[i],
                fontSize: 14,
                flex: '0 0 auto',
              }}
            >
              {t.icon}
            </div>
            {/* 文字 */}
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 12, fontWeight: 800, marginBottom: 1, whiteSpace: 'nowrap' }}>{t.title}</div>
              <div style={{ fontSize: 10, color: '#55617d', lineHeight: 1.4 }}>{t.desc}</div>
            </div>
          </div>
        ))}
      </div>

      {/* 隐私承诺 */}
      <div
        style={{
          borderTop: '1px solid #edf0fb',
          textAlign: 'center',
          color: '#77819e',
          fontSize: 10,
          padding: '10px 0 0',
          marginTop: 12,
        }}
      >
        <span style={{ color: '#2147ff', marginRight: 5 }}>▣</span>
        我们的对话内容仅用于服务你，绝不外泄，请放心分享
      </div>
    </div>
  );
}
