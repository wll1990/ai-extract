'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { ThemeToggle } from '@/components/ui/ThemeToggle';

import React, { useState, useEffect } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { getToken, clearAuth } from '@/lib/storage';
import { getCurrentUser } from '@/lib/api/auth';

/** 导航项定义 */
interface NavItem {
  icon: string;
  label: string;
  path: string;
  roles: string[];
}

const NAV_ITEMS: NavItem[] = [
  // ── 产品功能 ──
  { icon: '🤖', label: '分身广场', path: '/skills', roles: ['super_admin', 'company_admin', 'employee'] },
  { icon: '👤', label: '我的空间', path: '/space/me', roles: ['employee'] },
  { icon: '🏢', label: '空间总览', path: '/spaces', roles: ['super_admin'] },
  { icon: '💼', label: '销冠访谈', path: '/interview/create', roles: ['super_admin', 'company_admin', 'employee'] },
  { icon: '📚', label: '经验广场', path: '/explore', roles: ['super_admin', 'company_admin', 'employee'] },
  // ── 管理后台 ──
  { icon: '📊', label: '工作台', path: '/admin', roles: ['super_admin'] },
  { icon: '📈', label: '数据看板', path: '/admin/insights', roles: ['super_admin'] },
  { icon: '🎯', label: '分身调优', path: '/admin/tuning', roles: ['super_admin'] },
  { icon: '👥', label: '用户管理', path: '/admin/users', roles: ['super_admin'] },
  { icon: '🤖', label: '分身管理', path: '/admin/skills', roles: ['super_admin'] },
  { icon: '📁', label: '素材管理', path: '/admin/materials', roles: ['super_admin'] },
  { icon: '💎', label: '萃取师经验库', path: '/admin/experts', roles: ['super_admin'] },
  { icon: '💬', label: '对话历史', path: '/admin/conversations', roles: ['super_admin'] },
  { icon: '🗺️', label: '场景地图', path: '/admin/coverage', roles: ['super_admin'] },
  { icon: '⚙️', label: 'IM管理', path: '/admin/im', roles: ['super_admin'] },
  { icon: '🔗', label: '合作方管理', path: '/admin/partners', roles: ['super_admin'] },
  { icon: '🪙', label: 'Token 统计', path: '/admin/token-usage', roles: ['super_admin'] },
];

/**
 * 应用外壳布局——含侧导航栏和登录检查
 */
export default function AppLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [user, setUser] = useState<{ name: string; role: string } | null>(null);
  const [loading, setLoading] = useState(true);

  // 公开页面无需检查：登录/注册页（修复原先 /register 被 401 弹回 /login 的问题）
  // 与 C 端分享页（/s/*，独立布局与凭证体系，不走 B 端 /auth/me）
  const isPublicPage = pathname === '/login' || pathname === '/register' || pathname.startsWith('/s/') || pathname.startsWith('/h5/') || pathname.startsWith('/i/');

  useEffect(() => {
    if (isPublicPage) { setLoading(false); return; }

    // JWT 由 HttpOnly Cookie 自动携带，直接调 /auth/me 验证
    getCurrentUser()
      .then(user => {
        setUser(user);
        if (pathname.startsWith('/admin') && user.role !== 'super_admin') {
          router.replace('/skills');
        }
      })
      .catch(() => { clearAuth(); router.replace('/login'); })
      .finally(() => setLoading(false));
  }, [pathname, isPublicPage, router]);

  if (isPublicPage) return <>{children}</>;
  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center bg-surface">
        <LoadingSpinner fullScreen={false} />
      </div>
    );
  }

  const role = user?.role || 'employee';
  const visibleNav = NAV_ITEMS.filter(item => item.roles.includes(role));

  /** 判断导航激活：一级路径精确匹配，子路径前缀匹配 */
  const isNavActive = (currentPath: string, navPath: string) => {
    if (currentPath === navPath) return true;
    // 一级路径（如 /admin、/skills）只精确匹配，避免子页面也高亮父级
    const depth = navPath.split('/').filter(Boolean).length;
    if (depth === 1) return false;
    // 子路径用前缀匹配（如 /admin/skills 匹配 /admin/skills/[id]/xxx）
    return currentPath.startsWith(navPath + '/');
  };

  return (
    <div className="flex h-screen bg-surface">
      {/* 侧导航栏 */}
      <aside className="flex w-[220px] flex-shrink-0 flex-col bg-surface-2 border-r border-border">
        <div className="px-5 py-6">
          <h1 className="text-lg font-semibold text-foreground">💎 AI萃取平台</h1>
        </div>

        <div className="px-3 pb-4">
          <ThemeToggle />
        </div>

        <nav className="flex-1 overflow-y-auto space-y-0.5 px-3">
          {visibleNav.map(item => (
            <button
              key={item.path + item.label}
              onClick={() => pathname === item.path ? router.refresh() : router.push(item.path)}
              className={`flex w-full items-center gap-3 rounded-sm px-3 py-2.5 text-body transition-colors ${
                isNavActive(pathname, item.path) ? 'bg-primary-light text-primary font-medium' : 'text-muted-foreground hover:bg-primary-light'
              }`}
            >
              <span className="text-lg">{item.icon}</span>
              <span>{item.label}</span>
            </button>
          ))}
        </nav>
      </aside>

      {/* 主内容区 */}
      <main className="flex-1 flex flex-col overflow-auto">
        {/* 顶部栏：用户信息 + 退出 */}
        {user && (
          <div className="flex items-center justify-end gap-3 px-6 py-2 border-b border-border bg-surface-2 flex-shrink-0">
            <span className="text-sm text-muted-foreground">
              {user.name} · {role === 'super_admin' ? '管理员' : '员工'}
            </span>
            <button
              onClick={() => { clearAuth(); router.push('/login'); }}
              className="text-xs rounded border border-primary px-3 py-1 text-muted-foreground hover:bg-primary hover:text-white transition-colors"
            >
              退出登录
            </button>
          </div>
        )}
        <div className="flex-1 overflow-auto">
          {children}
        </div>
      </main>
    </div>
  );
}
