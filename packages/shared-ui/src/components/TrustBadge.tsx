'use client';

import React from 'react';

const ICON_COLORS = ['#ff4d5f', '#2147ff', '#8b5cf6'];

interface TrustBadgeProps {
  /** 活跃颗粒总数 — 有值时第一列显示 "♡ XX 条真实经验" */
  grainCount?: number;
  /** 场景覆盖数 — 有值时第二列显示 "✦ XX 个业务场景" */
  sceneCount?: number;
  /** 满意度百分比 0-100 — 有值时第二列追加满意度 */
  satisfactionRate?: number;
  /** 最近活跃描述 — 有值时第三列显示 "▤ {lastActive}" */
  lastActive?: string;
}

/**
 * TrustBadge — 品牌信任条，三列横排。
 * 有数据时展示真实数字，无数据时回退到静态文案（向后兼容）。
 */
export function TrustBadge({ grainCount, sceneCount, satisfactionRate, lastActive }: TrustBadgeProps = {}) {
  const col1Title = grainCount != null && grainCount > 0 ? '真实经验' : '真实案例';
  const col1Desc = grainCount != null && grainCount > 0
    ? `${grainCount} 条销冠实战经验` : '销冠真实对话与文档提炼';

  const col2Title = sceneCount != null && sceneCount > 0 ? '场景覆盖' : '溯源可查';
  const col2Desc = sceneCount != null && sceneCount > 0
    ? `${sceneCount} 个业务场景${satisfactionRate != null && satisfactionRate > 0 ? ` · 👍${satisfactionRate}% 满意` : ''}`
    : '每句回答有据可依';

  const col3Title = lastActive ? '最近活跃' : '即学即用';
  const col3Desc = lastActive || '30秒拿到可执行话术';

  const traits = [
    { icon: '♡' as const, title: col1Title, desc: col1Desc },
    { icon: '✦' as const, title: col2Title, desc: col2Desc },
    { icon: '▤' as const, title: col3Title, desc: col3Desc },
  ];

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
        {traits.map((t, i) => (
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
