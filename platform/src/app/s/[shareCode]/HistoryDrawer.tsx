/**
 * [平台端副本]
 * 本文件从 frontend 复制而来，已做平台端适配：
 * - 所有 Tailwind className → 内联 style（解决 platform globals.css 缺失 CSS 变量导致透明背景）
 * - 添加关闭按钮（X）+ 左滑手势
 *
 * 原始文件: frontend 对应路径
 * 复制日期: 2026-08-02
 * 适配日期: 2026-08-06
 */

'use client';

import { useRef, useState, useCallback } from 'react';
import type { ConversationItem } from '@/lib/api/skill';

interface Props {
  open: boolean;
  onClose: () => void;
  conversations: ConversationItem[];
  currentConvId: string;
  onSwitch: (convId: string) => void;
  onDelete: (convId: string) => void;
  onNew: () => void;
  nickname: string;
  isGuest: boolean;
}

// ── Design tokens ──

const C = {
  bg: '#ffffff',
  text: '#0f172a',
  textMid: '#64748b',
  textLow: '#94a3b8',
  border: '#e2e8f0',
  primary: '#2563eb',
  primaryBg: '#eff6ff',
  danger: '#dc2626',
  surface: '#f1f5f9',
  overlay: 'rgba(15,23,42,0.4)',
};

/** 会话模式徽标 */
const MODE_TAGS: Record<string, { label: string; color: string; bg: string }> = {
  qa: { label: '问答', color: '#2563eb', bg: '#eff6ff' },
  talk: { label: '聊天', color: '#0891b2', bg: 'rgba(6,182,212,0.10)' },
  practice: { label: '对练', color: '#8a6a2f', bg: 'rgba(200,164,92,0.14)' },
  quick: { label: '快问', color: '#64748b', bg: '#f1f5f9' },
  discuss: { label: '讨论', color: '#64748b', bg: '#f1f5f9' },
};

/**
 * 左侧历史会话抽屉（☰ 触发，85% 宽滑入）。
 * 支持左滑手势关闭 + X 按钮关闭 + 遮罩点击关闭。
 */
export default function HistoryDrawer({
  open, onClose, conversations, currentConvId, onSwitch, onDelete, onNew, nickname, isGuest,
}: Props) {
  const [dragX, setDragX] = useState(0);
  const touchStartX = useRef(0);
  const touchStartY = useRef(0);
  const isDragging = useRef(false);

  const handleTouchStart = useCallback((e: React.TouchEvent) => {
    touchStartX.current = e.touches[0].clientX;
    touchStartY.current = e.touches[0].clientY;
    isDragging.current = false;
  }, []);

  const handleTouchMove = useCallback((e: React.TouchEvent) => {
    const dx = e.touches[0].clientX - touchStartX.current;
    const dy = e.touches[0].clientY - touchStartY.current;
    // Only track horizontal swipes (ignore vertical scrolling)
    if (!isDragging.current && Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 8) {
      isDragging.current = true;
    }
    if (isDragging.current && dx < 0) {
      setDragX(dx);
    }
  }, []);

  const handleTouchEnd = useCallback(() => {
    if (dragX < -80) {
      onClose();
    }
    setDragX(0);
    isDragging.current = false;
  }, [dragX, onClose]);

  // Reset drag when drawer closes
  const transformedDragX = open ? dragX : 0;

  return (
    <>
      {/* 遮罩 */}
      <div
        onClick={onClose}
        style={{
          position: 'fixed', inset: 0, zIndex: 40,
          background: C.overlay,
          transition: 'opacity 0.2s',
          opacity: open ? 1 : 0,
          pointerEvents: open ? 'auto' : 'none',
        }}
      />

      {/* 抽屉 */}
      <div
        onTouchStart={handleTouchStart}
        onTouchMove={handleTouchMove}
        onTouchEnd={handleTouchEnd}
        style={{
          position: 'fixed', bottom: 0, left: 0, top: 0, zIndex: 50,
          width: '85%', maxWidth: 360,
          background: C.bg,
          boxShadow: '0 0 30px rgba(0,0,0,0.12)',
          display: 'flex', flexDirection: 'column',
          transition: 'transform 0.2s ease-out',
          transform: open
            ? `translateX(${transformedDragX}px)`
            : 'translateX(-102%)',
        }}
      >
        {/* Header: close + title + new conv */}
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          borderBottom: `1px solid ${C.border}`,
          padding: 'calc(16px + env(safe-area-inset-top)) 12px 12px 16px',
        }}>
          <button
            onClick={onClose}
            aria-label="关闭"
            style={{
              width: 32, height: 32, display: 'flex', alignItems: 'center', justifyContent: 'center',
              borderRadius: 8, border: 'none', background: 'transparent', cursor: 'pointer',
              color: C.textLow, fontSize: 16, fontFamily: 'inherit',
            }}
          >✕</button>
          <span style={{ fontSize: 16, fontWeight: 600, color: C.text }}>历史对话</span>
          <button
            onClick={() => { onNew(); onClose(); }}
            style={{
              borderRadius: 100, border: `1.5px solid ${C.primary}`,
              background: 'transparent', cursor: 'pointer',
              padding: '5px 14px', fontSize: 12, fontWeight: 600,
              color: C.primary, fontFamily: 'inherit',
            }}
          >＋ 新对话</button>
        </div>

        {/* Conversation list */}
        <div style={{ flex: 1, overflowY: 'auto', padding: 8 }}>
          {conversations.length === 0 && (
            <div style={{ padding: '40px 0', textAlign: 'center', fontSize: 12, color: C.textLow }}>
              还没有历史对话
            </div>
          )}
          {conversations.map(c => {
            const tag = MODE_TAGS[c.mode] || MODE_TAGS.qa;
            const isActive = currentConvId === c.id;
            return (
              <div
                key={c.id}
                onClick={() => { onSwitch(c.id); onClose(); }}
                style={{
                  display: 'flex', alignItems: 'center', gap: 10, cursor: 'pointer',
                  padding: '10px 12px', borderRadius: 10, marginBottom: 2,
                  background: isActive ? C.primaryBg : 'transparent',
                  transition: 'background 0.15s',
                }}
                onMouseEnter={e => { if (!isActive) e.currentTarget.style.background = C.surface; }}
                onMouseLeave={e => { if (!isActive) e.currentTarget.style.background = 'transparent'; }}
              >
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{
                    fontSize: 14, fontWeight: 500, color: C.text,
                    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                  }}>{c.title || '未命名对话'}</div>
                  <div style={{ fontSize: 11, color: C.textLow, marginTop: 2 }}>
                    {c.updatedAt ? c.updatedAt.replace('T', ' ').substring(5, 16) : ''}
                  </div>
                </div>
                <span style={{
                  flexShrink: 0, borderRadius: 100, padding: '2px 8px',
                  fontSize: 10, fontWeight: 600,
                  color: tag.color, background: tag.bg,
                }}>{tag.label}</span>
                <button
                  onClick={e => { e.stopPropagation(); onDelete(c.id); }}
                  aria-label="删除对话"
                  style={{
                    flexShrink: 0, padding: 4, border: 'none', background: 'transparent',
                    cursor: 'pointer', color: C.textLow, borderRadius: 6,
                  }}
                  onMouseEnter={e => { e.currentTarget.style.color = C.danger; }}
                  onMouseLeave={e => { e.currentTarget.style.color = C.textLow; }}
                >
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
                    <path d="M4 7h16M10 11v6M14 11v6M6 7l1 13a1 1 0 001 1h8a1 1 0 001-1l1-13M9 7V5a1 1 0 011-1h4a1 1 0 011 1v2" />
                  </svg>
                </button>
              </div>
            );
          })}
        </div>

        {/* Footer: user info */}
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8,
          borderTop: `1px solid ${C.border}`,
          padding: '12px 16px calc(16px + env(safe-area-inset-bottom))',
          fontSize: 12, color: C.textMid,
        }}>
          <span style={{
            width: 24, height: 24, borderRadius: '50%', flexShrink: 0,
            background: 'linear-gradient(135deg, #93c5fd, #3b82f6)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            color: '#fff', fontSize: 10, fontWeight: 700,
          }}>
            {nickname.charAt(0) || '客'}
          </span>
          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {nickname}{isGuest ? ' · 注册后跨设备保留记录' : ''}
          </span>
        </div>
      </div>
    </>
  );
}
