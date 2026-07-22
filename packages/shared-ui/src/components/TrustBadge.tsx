'use client';

import React from 'react';

/* ── 内联 SVG icon（三端统一，不依赖外部图标库）── */

function StarIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" style={{ width: 16, height: 16 }}>
      <path d="M12 2l3.5 7L22 9l-5.5 7.5L18 22l-6-4.5L6 22l1.5-5.5L2 9l6.5 0L12 2z" />
    </svg>
  );
}

function ShieldIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" style={{ width: 16, height: 16 }}>
      <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
      <path d="M14 2v6h6" />
      <circle cx="11.5" cy="14.5" r="2.5" />
      <path d="M13.5 16.5L16 19" />
    </svg>
  );
}

function BoltIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="white" stroke="white" strokeWidth="0.5" strokeLinecap="round" strokeLinejoin="round" style={{ width: 16, height: 16 }}>
      <path d="M13 2L3 14h7l-2 8 10-12h-7z" />
    </svg>
  );
}

/* ── 数据 ── */

const BADGES = [
  { Icon: StarIcon,   title: '实战打法', desc: '销冠真实案例提炼' },
  { Icon: ShieldIcon, title: '溯源可查', desc: '每句话有据可依' },
  { Icon: BoltIcon,   title: '即问即用', desc: '30秒拿到可执行话术' },
];

const ICON_COLORS = ['#f59e0b', '#6366f1', '#06b6d4'];

/* ── 组件 ── */

/**
 * 信任卡片 — 三列品牌信任条。
 *
 * 通过 CSS 变量换肤，三端共用同一组件：
 *   --trust-bg        背景
 *   --trust-border    边框
 *   --trust-max-w     最大宽度
 *   --trust-divider   列分隔线颜色
 */
export function TrustBadge() {
  return (
    <div
      style={{
        width: 'var(--trust-width, 100%)',
        padding: '12px 16px 10px',
        borderRadius: 12,
        background: 'var(--trust-bg, linear-gradient(135deg, rgba(6,182,212,0.04), rgba(59,130,246,0.06)))',
        border: '1px solid var(--trust-border, rgba(59,130,246,0.08))',
      }}
    >
      {/* 三列 badge */}
      <div style={{ display: 'flex', width: '100%' }}>
        {BADGES.map(({ Icon, title, desc }, i) => (
          <div
            key={i}
            style={{
              flex: 1,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 2,
              padding: '0 4px',
              borderRight:
                i < BADGES.length - 1
                  ? '1px solid var(--trust-divider, rgba(0,0,0,0.05))'
                  : 'none',
            }}
          >
            <div
              style={{
                width: 32,
                height: 32,
                borderRadius: '50%',
                background: `linear-gradient(135deg, ${ICON_COLORS[i]}, ${ICON_COLORS[i]}dd)`,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <Icon />
            </div>
            <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--fg-high, #1A1D23)' }}>
              {title}
            </span>
            <span style={{ fontSize: 11, color: 'var(--fg-dim, #94A3B8)', lineHeight: 1.3 }}>
              {desc}
            </span>
          </div>
        ))}
      </div>

      {/* 隐私承诺 — 置底细线分隔 */}
      <div style={{
        marginTop: 10,
        paddingTop: 8,
        borderTop: '1px solid var(--trust-divider, rgba(0,0,0,0.04))',
      }}>
        <p style={{
          margin: 0,
          fontSize: 10,
          lineHeight: 1.5,
          color: 'var(--fg-dim, #94A3B8)',
          textAlign: 'center',
          letterSpacing: '0.03em',
        }}>
          &#x25A3;&ensp;我们的对话内容仅用于服务你，绝不外泄，请放心分享
        </p>
      </div>
    </div>
  );
}
