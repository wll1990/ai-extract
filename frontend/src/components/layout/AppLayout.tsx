'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { ThemeToggle } from '@/components/ui/ThemeToggle';
import { Permission } from '@/lib/permissions';

import React, { useState, useEffect } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { clearAuth } from '@/lib/storage';
import { getCurrentUser } from '@/lib/api/auth';

interface UserInfo {
  id: string;
  name: string;
  role: string;
  avatarUrl: string | null;
  companyId: string;
  companyName: string;
  permissions: string[];
}

/** 导航项定义 */
interface NavItem {
  icon: string;
  label: string;
  path: string;
  /** 需要的权限码，null 表示不校验（始终显示） */
  permission: string | null;
}

const NAV_ITEMS: NavItem[] = [
  // ── 产品功能 ──
  { icon: '🤖', label: '分身广场', path: '/skills', permission: Permission.SKILL_USE },
  { icon: '👤', label: '我的空间', path: '/space/me', permission: Permission.SPACE_OWN },
  { icon: '🏢', label: '空间总览', path: '/spaces', permission: Permission.USER_MANAGE },
  { icon: '💼', label: '销冠访谈', path: '/interview/create', permission: Permission.SKILL_USE },
  { icon: '📚', label: '经验广场', path: '/explore', permission: Permission.SKILL_USE },
  { icon: '🏠', label: '我的工作台', path: '/workbench', permission: Permission.SKILL_USE },
  // ── 管理后台 ──
  { icon: '📊', label: '工作台', path: '/admin', permission: Permission.DASHBOARD_VIEW },
  { icon: '📈', label: '数据看板', path: '/admin/insights', permission: Permission.DASHBOARD_VIEW },
  { icon: '🎯', label: '分身调优', path: '/admin/tuning', permission: Permission.SKILL_TUNING },
  { icon: '👥', label: '用户管理', path: '/admin/users', permission: Permission.USER_MANAGE },
  { icon: '🤖', label: '分身管理', path: '/admin/skills', permission: Permission.SKILL_MANAGE },
  { icon: '📁', label: '素材管理', path: '/admin/materials', permission: Permission.MATERIAL_MANAGE },
  { icon: '💎', label: '萃取师经验库', path: '/admin/experts', permission: Permission.EXPERT_MANAGE },
  { icon: '💬', label: '对话历史', path: '/admin/conversations', permission: Permission.CONVERSATION_VIEW },
  { icon: '🗺️', label: '场景地图', path: '/admin/coverage', permission: Permission.SCENE_COVERAGE },
  { icon: '💎', label: '颗粒管理', path: '/admin/grains', permission: Permission.GRAIN_MANAGE },
  { icon: '🏢', label: '组织分身', path: '/admin/organization-skills', permission: Permission.ORG_SKILL_MANAGE },
  { icon: '🪙', label: 'Token 统计', path: '/admin/token-usage', permission: Permission.TOKEN_VIEW_COMPANY },
  // ── 平台级（仅 super_admin 拥有对应权限码） ──
  { icon: '⚙️', label: 'IM管理', path: '/admin/im', permission: Permission.IM_MANAGE },
  { icon: '🔗', label: '合作方管理', path: '/admin/partners', permission: Permission.PARTNER_MANAGE },
  { icon: '🏢', label: '企业合作', path: '/admin/companies', permission: Permission.COMPANY_MANAGE },
];

/** 判断当前用户是否可以访问管理后台 */
function canAccessAdmin(permissions: string[]): boolean {
  return permissions.some(
    p => p === Permission.DASHBOARD_VIEW
      || p === Permission.USER_MANAGE
      || p === Permission.SKILL_MANAGE
      || p === Permission.TOKEN_VIEW_COMPANY
      || p === Permission.MATERIAL_MANAGE
  );
}

/** 根据权限码推导用户的显示角色标签 */
function roleLabel(permissions: string[]): string {
  if (permissions.includes(Permission.COMPANY_MANAGE)) return '超级管理员';
  if (permissions.includes(Permission.USER_MANAGE)) return '企业管理员';
  return '员工';
}

/**
 * 应用外壳布局——含侧导航栏和登录检查
 */
export default function AppLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [user, setUser] = useState<UserInfo | null>(null);
  const [loading, setLoading] = useState(true);

  const isPublicPage = pathname === '/login' || pathname === '/register'
    || pathname.startsWith('/s/') || pathname.startsWith('/h5/') || pathname.startsWith('/i/');

  // 从公开页进入需登录页时重新获取用户信息
  useEffect(() => {
    if (isPublicPage) { setUser(null); setLoading(false); return; }
    let cancelled = false;
    getCurrentUser()
      .then(u => { if (!cancelled) setUser(u as unknown as UserInfo); })
      .catch(() => { if (!cancelled) { clearAuth(); router.replace('/login'); } })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [isPublicPage]);

  // 路由变化时用缓存 user 校验 admin 权限
  useEffect(() => {
    if (user && pathname.startsWith('/admin') && !canAccessAdmin(user.permissions || [])) {
      router.replace('/skills');
    }
  }, [pathname, user]);

  if (isPublicPage) return <>{children}</>;
  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center bg-surface">
        <LoadingSpinner fullScreen={false} />
      </div>
    );
  }

  const permissions: string[] = user?.permissions || [];

  /** 判断导航激活：一级路径精确匹配，子路径前缀匹配 */
  const isNavActive = (currentPath: string, navPath: string) => {
    if (currentPath === navPath) return true;
    // "我的空间" 跳转到 /space/{id} 后仍应高亮
    if (navPath === '/space/me' && currentPath.startsWith('/space/') && currentPath !== '/spaces' && !currentPath.startsWith('/spaces/')) return true;
    const depth = navPath.split('/').filter(Boolean).length;
    if (depth === 1) return false;
    return currentPath.startsWith(navPath + '/');
  };

  const visibleNav = NAV_ITEMS.filter(item =>
    !item.permission || permissions.includes(item.permission)
  );

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
                isNavActive(pathname, item.path)
                  ? 'bg-primary-light text-primary font-medium'
                  : 'text-muted-foreground hover:bg-primary-light'
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
        {user && (
          <div className="flex items-center justify-end gap-3 px-6 py-2 border-b border-border bg-surface-2 flex-shrink-0">
            <span className="text-sm text-muted-foreground">
              {user.name} · {roleLabel(permissions)}
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
