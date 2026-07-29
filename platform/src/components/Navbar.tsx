'use client';

import { useState, useEffect, useCallback } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { getUser, clearAuth } from '@/lib/storage';
import { logout as logoutApi } from '@/lib/api/auth';

export function Navbar() {
  const router = useRouter();
  const [user, setUser] = useState<{ name: string; role: string; avatarUrl?: string } | null>(null);
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    const u = getUser();
    if (u && u.name) {
      setUser({ name: u.name, role: u.role as string, avatarUrl: u.avatarUrl as string | undefined });
    }
    setMounted(true);
  }, []);

  const handleLogout = useCallback(async () => {
    try { await logoutApi(); } catch { /* 即使 API 失败也清除本地状态 */ }
    clearAuth();
    setUser(null);
    router.push('/');
  }, [router]);

  // SSR 阶段和客户端首帧一致：始终先渲染未登录态，hydration 后再更新
  const isLoggedIn = mounted && user !== null;
  const initial = user?.name?.[0] || '?';

  return (
    <nav
      style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '14px 40px', maxWidth: 1280, margin: '0 auto',
        position: 'relative', zIndex: 100,
      }}
    >
      <Link href="/" style={{
        fontWeight: 800, fontSize: 17, letterSpacing: '-0.03em',
        display: 'flex', alignItems: 'center', gap: 8,
        textDecoration: 'none', color: 'var(--fg-high)',
      }}>
        <span style={{
          width: 32, height: 32, borderRadius: 9,
          background: 'linear-gradient(135deg, var(--s12), var(--tangerine))',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: 16,
        }}>💎</span>
        MindForge
      </Link>

      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <Link href="/discover" className="btn btn-ghost">发现专家</Link>

        {mounted && isLoggedIn && (
          <Link href="/platform/my" className="btn btn-ghost">我的分身</Link>
        )}

        {!mounted ? (
          // hydration 前与 SSR 一致：显示占位，避免布局跳动
          <span style={{ width: 56, height: 34 }} />
        ) : isLoggedIn ? (
          <>
            {/* 用户头像 + 姓名 */}
            <div style={{
              display: 'flex', alignItems: 'center', gap: 8,
              padding: '4px 12px 4px 4px', borderRadius: 100,
              background: 'var(--s3)',
            }}>
              {user?.avatarUrl ? (
                <img
                  src={user.avatarUrl}
                  alt={user.name}
                  style={{
                    width: 26, height: 26, borderRadius: 8,
                    objectFit: 'cover',
                  }}
                />
              ) : (
                <span style={{
                  width: 26, height: 26, borderRadius: 8,
                  background: 'linear-gradient(135deg, var(--s12), var(--tangerine))',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  color: '#fff', fontSize: 12, fontWeight: 700,
                  flexShrink: 0,
                }}>
                  {initial}
                </span>
              )}
              <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--fg-high)' }}>
                {user?.name}
              </span>
            </div>

            {/* 退出按钮 */}
            <button
              onClick={handleLogout}
              style={{
                background: 'none', border: 'none', cursor: 'pointer',
                color: 'var(--fg-low)', fontSize: 12, padding: '6px 8px',
                borderRadius: 8, transition: 'color 0.15s',
                fontFamily: 'inherit',
              }}
              onMouseEnter={(e) => { e.currentTarget.style.color = 'var(--fg-high)'; }}
              onMouseLeave={(e) => { e.currentTarget.style.color = 'var(--fg-low)'; }}
            >
              退出
            </button>
          </>
        ) : (
          <Link href="/login" className="btn btn-primary">登录</Link>
        )}
      </div>
    </nav>
  );
}
