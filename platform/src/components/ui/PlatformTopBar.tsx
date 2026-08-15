'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { getUser, clearAuth } from '@/lib/storage';
import { logout as logoutApi } from '@/lib/api/auth';

interface Props {
  backTo?: string;
  backLabel?: string;
  title?: string;
}

const C = {
  barBg: '#ffffff',
  border: '#e2e8f0',
  text: '#0f172a',
  textMid: '#475569',
  textLow: '#94a3b8',
  accent: '#2563eb',
};

export default function PlatformTopBar({ backTo, backLabel, title }: Props) {
  const router = useRouter();
  const [user, setUser] = useState<{ name: string; role: string; avatarUrl?: string } | null>(null);
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    const u = getUser();
    if (u?.name) setUser({ name: u.name, role: u.role as string, avatarUrl: u.avatarUrl as string | undefined });
    setMounted(true);
  }, []);

  const handleLogout = async () => {
    try { await logoutApi(); } catch { /* ignore */ }
    clearAuth();
    setUser(null);
    router.push('/');
  };

  const initial = user?.name?.[0] || '?';

  return (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      height: 48, padding: '0 20px', background: C.barBg,
      borderBottom: `1px solid ${C.border}`,
      position: 'sticky', top: 0, zIndex: 100,
    }}>
      {/* Left: back button */}
      <div style={{ display: 'flex', alignItems: 'center', minWidth: 120 }}>
        {(backTo || backLabel) && (
          <button
            onClick={() => backTo ? router.push(backTo) : router.back()}
            style={{
              display: 'inline-flex', alignItems: 'center', gap: 6,
              background: 'none', border: 'none', cursor: 'pointer',
              padding: '4px 8px', borderRadius: 6,
              fontSize: 13, color: C.textMid, fontFamily: 'inherit',
              transition: 'all 0.15s', marginLeft: -8,
            }}
            onMouseEnter={e => { e.currentTarget.style.background = '#f1f5f9'; e.currentTarget.style.color = C.text; }}
            onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = C.textMid; }}
          >
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M10 3 L5 8 L10 13" />
            </svg>
            {backLabel || '返回'}
          </button>
        )}
      </div>

      {/* Center: title */}
      <div style={{ textAlign: 'center' }}>
        {title && (
          <span style={{ fontSize: 14, fontWeight: 600, color: C.text }}>{title}</span>
        )}
      </div>

      {/* Right: user info */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', minWidth: 120 }}>
        {!mounted ? (
          <span style={{ width: 32, height: 32 }} />
        ) : user && user.role !== 'guest' ? (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <div style={{
              display: 'flex', alignItems: 'center', gap: 8,
              padding: '3px 12px 3px 3px', borderRadius: 100,
              background: '#f8fafc', border: `1px solid ${C.border}`,
            }}>
              <div style={{
                width: 28, height: 28, borderRadius: 8, flexShrink: 0,
                background: `linear-gradient(135deg, ${C.accent}, #6366f1)`,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                color: '#fff', fontSize: 13, fontWeight: 700,
              }}>
                {initial}
              </div>
              <span style={{ fontSize: 12, fontWeight: 500, color: C.textMid, maxWidth: 80, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {user.name}
              </span>
            </div>
            <button
              onClick={handleLogout}
              style={{
                background: 'none', border: 'none', cursor: 'pointer',
                fontSize: 12, color: C.textLow, padding: '4px 6px',
                borderRadius: 6, fontFamily: 'inherit', transition: 'all 0.15s',
              }}
              onMouseEnter={e => { e.currentTarget.style.color = C.textMid; e.currentTarget.style.background = '#f1f5f9'; }}
              onMouseLeave={e => { e.currentTarget.style.color = C.textLow; e.currentTarget.style.background = 'transparent'; }}
            >
              退出
            </button>
          </div>
        ) : (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <button
              onClick={() => router.push('/login')}
              style={{
                padding: '5px 14px', borderRadius: 100, cursor: 'pointer',
                border: 'none', background: C.accent, color: '#fff',
                fontSize: 12, fontWeight: 600, fontFamily: 'inherit',
              }}
            >
              登录
            </button>
            <button
              onClick={() => router.push('/register')}
              style={{
                padding: '5px 14px', borderRadius: 100, cursor: 'pointer',
                border: `1px solid ${C.border}`, background: '#fff', color: C.textMid,
                fontSize: 12, fontWeight: 600, fontFamily: 'inherit',
              }}
            >
              注册
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
