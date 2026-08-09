'use client';

import React, { useState } from 'react';

type Role = 'ai' | 'user' | 'customer';

interface ChatAvatarProps {
  role: Role;
  src?: string;
  size?: number;
}

const ROLE_CONFIG: Record<Role, { label: string; bg: string }> = {
  ai:       { label: '专', bg: '#6366f1' },
  user:     { label: '我', bg: '#3b82f6' },
  customer: { label: '客', bg: '#f59e0b' },
};

/**
 * ChatAvatar — 聊天消息小头像。
 * 有照片显示照片，没照片或加载失败显示单字+纯色底。
 */
export function ChatAvatar({ role, src, size = 32 }: ChatAvatarProps) {
  const { label, bg } = ROLE_CONFIG[role];
  const fontSize = size < 30 ? size * 0.42 : size * 0.38;
  const [imgFailed, setImgFailed] = useState(false);

  const showImg = src && !imgFailed;

  return (
    <div
      style={{
        width: size,
        height: size,
        borderRadius: '50%',
        overflow: 'hidden',
        flexShrink: 0,
        background: showImg ? undefined : bg,
        boxShadow: '0 2px 6px rgba(0,0,0,0.1)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      {showImg ? (
        <img src={src} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }}
          onError={() => setImgFailed(true)} />
      ) : (
        <span style={{
          color: '#fff',
          fontSize,
          fontWeight: 700,
          lineHeight: 1,
          userSelect: 'none',
        }}>
          {label}
        </span>
      )}
    </div>
  );
}
