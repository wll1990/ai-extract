'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { ThemeToggle } from '@/components/ui/ThemeToggle';
import { CommandPalette } from '@/components/layout/CommandPalette';
import { HelpMenu } from '@/components/layout/HelpMenu';
import { NavIcon } from '@/components/layout/NavIcon';
import { Permission } from '@/lib/permissions';
import {
  DOMAINS,
  DOMAIN_TITLE,
  activeNavPath,
  isNavDomainKey,
  resolveDomain,
  type NavBadges,
  type NavDomainKey,
  type NavItem,
} from '@/lib/nav';

import React, { useState, useEffect, useRef } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { Bell, CaretDown, List, Question, SignOut, UserCircle } from '@phosphor-icons/react';
import { clearAuth } from '@/lib/storage';
import { getCurrentUser } from '@/lib/api/auth';
import { apiClient } from '@/lib/api/client';
import {
  beginActingSwitch,
  endActingSwitch,
  getActingCompanyId,
  getActingCompanyName,
  setActingCompany,
} from '@/lib/acting-company';
import {
  listNotifications, markAllNotificationsRead, markNotificationRead,
  unreadNotificationCount, type NotificationItem,
} from '@/lib/api/notifications';

interface UserInfo {
  id: string;
  name: string;
  role: string;
  avatarUrl: string | null;
  companyId: string;
  companyName: string;
  permissions: string[];
}

interface CompanyOption {
  id: string;
  name: string;
  status?: string;
}

/** 接口失败时的本地回退菜单；正常渲染以 /config/nav 为准 */
const NAV_ITEMS: NavItem[] = [
  { path: '/skills', label: '分身广场', permission: Permission.SKILL_USE, domain: 'daily' },
  { path: '/workbench', label: '我的工作台', permission: Permission.SKILL_USE, domain: 'daily' },
  { path: '/explore', label: '经验广场', permission: Permission.SKILL_USE, domain: 'daily' },
  { path: '/interview/create', label: '销冠访谈', permission: Permission.SKILL_USE, domain: 'daily' },
  { path: '/space/me', label: '我的空间', permission: Permission.SPACE_OWN, domain: 'daily' },
  { path: '/admin/materials', label: '上传素材', permission: Permission.MATERIAL_MANAGE, domain: 'daily', roles: ['employee'] },

  { path: '/sales/dashboard', label: '我的销售看板', permission: Permission.SALES_REVIEW, domain: 'sales' },
  { path: '/sales/reviews', label: '销售复盘', permission: Permission.SALES_REVIEW, domain: 'sales' },
  { path: '/sales/leads', label: '线索', permission: Permission.SALES_LEAD_MANAGE, domain: 'sales' },
  { path: '/sales/tasks', label: '跟进任务', permission: Permission.SALES_LEAD_MANAGE, domain: 'sales' },
  { path: '/sales/coach/ledger', label: '教练台账', permission: Permission.SALES_REVIEW, domain: 'sales' },
  { path: '/training', label: '我的培训', permission: Permission.TRAINING_LEARN, domain: 'sales' },
  { path: '/admin/training/plans', label: '培训计划', permission: Permission.TRAINING_MANAGE, domain: 'sales' },

  { path: '/admin/skills', label: '分身管理', permission: Permission.SKILL_MANAGE, domain: 'production' },
  { path: '/admin/materials', label: '素材管理', permission: Permission.MATERIAL_MANAGE, domain: 'production', roles: ['company_admin', 'super_admin'] },
  { path: '/admin/grains', label: '经验颗粒', permission: Permission.GRAIN_MANAGE, domain: 'production' },
  { path: '/admin/tuning', label: '分身调优', permission: Permission.SKILL_TUNING, domain: 'production' },
  { path: '/admin/organization-skills', label: '组织分身', permission: Permission.ORG_SKILL_MANAGE, domain: 'production' },
  { path: '/admin/im-bots', label: 'IM 分身接入', permission: Permission.IM_MANAGE, domain: 'production' },

  { path: '/admin/knowledge', label: '知识库', permission: Permission.KNOWLEDGE_MANAGE, domain: 'knowledge' },
  { path: '/admin/cases', label: '案例库', permission: Permission.KNOWLEDGE_MANAGE, domain: 'knowledge' },
  { path: '/admin/content', label: '内容工厂', permission: Permission.KNOWLEDGE_MANAGE, domain: 'knowledge' },
  { path: '/knowledge/submissions', label: '提交知识', permission: Permission.KNOWLEDGE_SUBMIT, domain: 'knowledge' },
  { path: '/cases/submissions', label: '提交案例', permission: Permission.KNOWLEDGE_SUBMIT, domain: 'knowledge' },
  { path: '/admin/experts', label: '专家经验', permission: Permission.EXPERT_MANAGE, domain: 'knowledge' },
  { path: '/admin/coverage', label: '场景地图', permission: Permission.SCENE_COVERAGE, domain: 'knowledge' },
  { path: '/admin/insights', label: '数据看板', permission: Permission.DASHBOARD_VIEW, domain: 'knowledge' },
  { path: '/admin/evaluations', label: '评测中心', permission: Permission.EVAL_MANAGE, domain: 'knowledge' },

  { path: '/admin', label: '运营总览', permission: Permission.DASHBOARD_VIEW, domain: 'manage' },
  { path: '/admin/users', label: '用户管理', permission: Permission.USER_MANAGE, domain: 'manage' },
  { path: '/spaces', label: '成员空间', permission: Permission.USER_MANAGE, domain: 'manage' },
  { path: '/admin/conversations', label: '对话历史', permission: Permission.CONVERSATION_VIEW, domain: 'manage' },
  { path: '/admin/token-usage', label: 'Token 统计', permission: Permission.TOKEN_VIEW_COMPANY, domain: 'manage' },
  { path: '/admin/settings/ai-model', label: 'AI 模型配置', permission: Permission.AI_MODEL_MANAGE, domain: 'manage' },
  { path: '/admin/settings/skills', label: '模型技能', permission: Permission.SKILL_MANAGE, domain: 'manage' },

  { path: '/admin/partners', label: '合作方管理', permission: Permission.PARTNER_MANAGE, domain: 'platform' },
  { path: '/admin/companies', label: '企业合作', permission: Permission.COMPANY_MANAGE, domain: 'platform' },
  { path: '/admin/demo-requests', label: '预约演示', permission: Permission.COMPANY_MANAGE, domain: 'platform' },
];

/** 判断当前用户是否可以访问管理后台 */
function canAccessAdmin(permissions: string[]): boolean {
  return permissions.some(
    p => p === Permission.DASHBOARD_VIEW
      || p === Permission.USER_MANAGE
      || p === Permission.SKILL_MANAGE
      || p === Permission.TOKEN_VIEW_COMPANY
      || p === Permission.KNOWLEDGE_MANAGE
      || p === Permission.TRAINING_MANAGE
      || p === Permission.EVAL_MANAGE
  );
}

/**
 * 路径级管理权限。
 *
 * 导师（employee）只有 TRAINING_REVIEW：可以进批次详情/进度，但不能进计划管理或其它后台页面；
 * 不能因为一个培训权限就放开整个 /admin/*。
 */
function canAccessPath(permissions: string[], pathname: string): boolean {
  if (!pathname.startsWith('/admin')) return true;
  if (pathname.startsWith('/admin/training/cohorts')) {
    return permissions.includes(Permission.TRAINING_REVIEW)
      || permissions.includes(Permission.TRAINING_MANAGE);
  }
  if (pathname.startsWith('/admin/training')) {
    return permissions.includes(Permission.TRAINING_MANAGE);
  }
  // 员工只有素材权限：只放行素材相关页，不再整段放行 /admin
  if (pathname.startsWith('/admin/skills/upload') || pathname.startsWith('/admin/materials')) {
    return permissions.includes(Permission.MATERIAL_MANAGE) || canAccessAdmin(permissions);
  }
  return canAccessAdmin(permissions);
}

/** 根据权限码推导用户的显示角色标签 */
function roleLabel(permissions: string[]): string {
  if (permissions.includes(Permission.COMPANY_MANAGE)) return '超级管理员';
  if (permissions.includes(Permission.USER_MANAGE)) return '企业管理员';
  return '员工';
}

function isServerNavItem(v: unknown): v is { path: string; label: string; permission: string; domain: string } {
  if (!v || typeof v !== 'object') return false;
  const item = v as Record<string, unknown>;
  return typeof item.path === 'string'
    && typeof item.label === 'string'
    && typeof item.permission === 'string'
    && isNavDomainKey(item.domain);
}

/**
 * 应用外壳布局——顶栏分区 + 域内二级菜单。
 */
export default function AppLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [user, setUser] = useState<UserInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [serverNav, setServerNav] = useState<NavItem[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [badges, setBadges] = useState<NavBadges>({ domains: {}, items: {} });
  const [unread, setUnread] = useState(0);
  const [bellOpen, setBellOpen] = useState(false);
  const [notices, setNotices] = useState<NotificationItem[]>([]);
  const [bellPage, setBellPage] = useState(1);
  const [bellTotalPages, setBellTotalPages] = useState(1);
  const [bellLoading, setBellLoading] = useState(false);
  const [bellError, setBellError] = useState('');
  const [domainOverride, setDomainOverride] = useState<NavDomainKey | null>(null);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  // 超管企业视角
  const [companyMenuOpen, setCompanyMenuOpen] = useState(false);
  const [companies, setCompanies] = useState<CompanyOption[]>([]);
  const [companiesLoading, setCompaniesLoading] = useState(false);
  const [companiesError, setCompaniesError] = useState('');
  const [companiesReloadKey, setCompaniesReloadKey] = useState(0);
  const [companyKeyword, setCompanyKeyword] = useState('');
  const [switchingCompany, setSwitchingCompany] = useState(false);
  const [actingError, setActingError] = useState('');
  const [actingId, setActingId] = useState<string | null>(null);
  const [actingName, setActingName] = useState<string | null>(null);

  const isPublicPage = pathname === '/login' || pathname === '/register'
    || pathname.startsWith('/s/') || pathname.startsWith('/h5/') || pathname.startsWith('/i/');

  // 销冠门户有自己的外壳与导航，不能套后台侧边栏
  const isPortalPage = pathname.startsWith('/portal');

  // 从公开页进入需登录页时重新获取用户信息
  useEffect(() => {
    if (isPublicPage || isPortalPage) { setUser(null); setLoading(false); return; }
    let cancelled = false;
    getCurrentUser()
      .then(u => { if (!cancelled) { setUser(u as unknown as UserInfo); setLoadError(false); } })
      // 401 已由 apiClient 统一清凭证跳登录；其它错误（500/断网）不能伪装成会话过期
      .catch(() => { if (!cancelled) setLoadError(true); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [isPublicPage, isPortalPage, router]);

  // 视角来自 sessionStorage；用户变化（登录/切换账号）时重新读取
  useEffect(() => {
    setActingId(getActingCompanyId());
    setActingName(getActingCompanyName());
  }, [user]);

  // 企业下拉数据只在超管第一次展开时加载
  useEffect(() => {
    if (!companyMenuOpen) return;
    const perms = user?.permissions || [];
    if (!perms.includes(Permission.COMPANY_MANAGE) || companies.length > 0) return;
    let cancelled = false;
    setCompaniesLoading(true);
    setCompaniesError('');
    apiClient<{ content: CompanyOption[] }>('/admin/companies?page=1&size=100')
      .then(d => {
        if (cancelled) return;
        setCompanies((d.content || []).filter(c => c.status !== 'archived'));
      })
      .catch(e => {
        if (!cancelled) setCompaniesError(e?.message || '企业列表加载失败');
      })
      .finally(() => {
        if (!cancelled) setCompaniesLoading(false);
      });
    return () => { cancelled = true; };
  }, [companyMenuOpen, user, companies.length, companiesReloadKey]);

  // 路由变化时用缓存 user 校验 admin 权限
  useEffect(() => {
    if (!isPortalPage && user && !canAccessPath(user.permissions || [], pathname)) {
      const perms = user.permissions || [];
      const onlyTraining = perms.includes(Permission.TRAINING_REVIEW)
        || perms.includes(Permission.TRAINING_LEARN);
      router.replace(onlyTraining ? '/training' : '/skills');
    }
  }, [pathname, user, router, isPortalPage]);

  // 菜单服务端下发：后端按权限过滤，前端只补图标。
  // 成功结果缓存一份，接口抖动时用缓存；再失败才回退本地 NAV_ITEMS。
  useEffect(() => {
    if (isPublicPage || isPortalPage) return;
    // 缓存按视角隔离，切换企业后不会回退到上一个企业的菜单
    const navCacheKey = `nav.cache.${getActingCompanyId() ?? 'all'}`;
    const apply = (raw: unknown[]) => {
      const items = raw.filter(isServerNavItem).map(i => ({
        path: i.path,
        label: i.label,
        permission: i.permission,
        domain: i.domain as NavDomainKey,
      }));
      setServerNav(items.length > 0 ? items : null);
    };
    apiClient<unknown[]>('/config/nav')
      .then(items => {
        apply(items);
        try { localStorage.setItem(navCacheKey, JSON.stringify(items)); } catch { /* 忽略配额 */ }
      })
      .catch(() => {
        try {
          const cached = localStorage.getItem(navCacheKey);
          if (cached) {
            apply(JSON.parse(cached));
            return;
          }
        } catch { /* 缓存损坏时回退本地菜单 */ }
        setServerNav(null);
      });
  }, [isPublicPage, isPortalPage, actingId]);

  // 导航红点：待审核分身 / 待处理素材 / 待回复教练请求
  useEffect(() => {
    if (isPublicPage || isPortalPage || !user) return;
    let cancelled = false;
    apiClient<NavBadges>('/config/nav/badges')
      .then(b => {
        if (cancelled) return;
        setBadges({ domains: b?.domains || {}, items: b?.items || {} });
      })
      .catch(() => { /* 红点失败静默降级，不影响导航 */ });
    return () => { cancelled = true; };
  }, [isPublicPage, isPortalPage, user]);

  // ⌘K / Ctrl+K 打开快捷入口
  useEffect(() => {
    if (isPublicPage || isPortalPage) return;
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen(v => !v);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [isPublicPage, isPortalPage]);

  // 站内信红点：60s 轮询一次；失败静默降级为无红点，不影响布局
  // 已读操作会让在途的旧计数作废，防止红点被过期响应「复活」
  const unreadSeq = useRef(0);
  useEffect(() => {
    if (isPublicPage || isPortalPage || !user) return;
    let cancelled = false;
    const load = () => {
      const seq = unreadSeq.current;
      unreadNotificationCount()
        .then(r => { if (!cancelled && seq === unreadSeq.current) setUnread(r.count || 0); })
        .catch(() => { /* 忽略 */ });
    };
    load();
    const timer = setInterval(load, 60000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [isPublicPage, isPortalPage, user]);

  // 移动端：路由变化后收起抽屉
  useEffect(() => { setMobileNavOpen(false); }, [pathname]);

  // Esc 关闭顶栏浮层（通知 / 账号菜单）
  useEffect(() => {
    if (!bellOpen && !userMenuOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { setBellOpen(false); setUserMenuOpen(false); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [bellOpen, userMenuOpen]);

  if (isPublicPage || isPortalPage) return <>{children}</>;
  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center bg-surface">
        <LoadingSpinner fullScreen={false} />
      </div>
    );
  }
  if (loadError) {
    return (
      <div className="flex h-screen flex-col items-center justify-center gap-3 bg-surface">
        <p className="text-sm text-muted-foreground">加载用户信息失败，请检查网络或稍后重试</p>
        <button
          onClick={() => window.location.reload()}
          className="rounded-full bg-primary px-5 py-2 text-sm text-white hover:opacity-90"
        >
          重试
        </button>
      </div>
    );
  }

  const permissions: string[] = user?.permissions || [];
  const visibleNav = serverNav ?? NAV_ITEMS.filter(item =>
    (!item.permission || permissions.includes(item.permission))
      && (!item.roles || item.roles.includes(user?.role || ''))
  );

  // 域由当前路径反推；反推不到（如 /help）时保留用户手动选择的域
  const pathDomain = resolveDomain(pathname, visibleNav);
  const domainsWithItems = DOMAINS
    .map(d => ({ ...d, items: visibleNav.filter(i => i.domain === d.key) }))
    .filter(d => d.items.length > 0);
  const activeDomain: NavDomainKey = pathDomain ?? domainOverride ?? domainsWithItems[0]?.key ?? 'daily';
  const activeItems = domainsWithItems.find(d => d.key === activeDomain)?.items ?? [];
  const activeTitle = DOMAIN_TITLE[activeDomain];
  // 最长前缀唯一高亮：避免 /admin/content 与 /admin/content/review 同时选中
  const activeItemPath = activeNavPath(pathname, activeItems);

  const switchDomain = (key: NavDomainKey) => {
    if (key === activeDomain) return;
    const first = visibleNav.find(i => i.domain === key);
    if (!first) return;
    setDomainOverride(key);
    router.push(first.path);
  };

  const openBell = async () => {
    if (bellOpen) { setBellOpen(false); return; }
    setBellOpen(true);
    setUserMenuOpen(false);
    setBellLoading(true);
    setBellError('');
    try {
      // 一次取 20 条并支持「加载更多」：只取 8 条会让更早的未读永远看不到
      const data = await listNotifications(1, 20, false);
      setNotices(data.content || []);
      setBellPage(1);
      setBellTotalPages(Math.max(1, data.totalPages || 1));
    } catch (e) {
      setNotices([]);
      setBellTotalPages(1);
      setBellError(e instanceof Error ? e.message : '通知加载失败');
    } finally {
      setBellLoading(false);
    }
  };

  const loadMoreNotices = async () => {
    if (bellLoading || bellPage >= bellTotalPages) return;
    setBellLoading(true);
    try {
      const next = bellPage + 1;
      const data = await listNotifications(next, 20, false);
      setNotices(prev => [...prev, ...(data.content || [])]);
      setBellPage(next);
      setBellTotalPages(Math.max(1, data.totalPages || 1));
    } catch { /* 保留已加载内容 */ } finally {
      setBellLoading(false);
    }
  };

  const openNotice = (notice: NotificationItem) => {
    setBellOpen(false);
    if (!notice.read) {
      unreadSeq.current++;
      // 先导航再标已读：慢网络下不让用户等一次写请求；失败由下一轮轮询纠正
      setUnread(prev => Math.max(0, prev - 1));
      markNotificationRead(notice.id).catch(() => { unreadSeq.current++; });
    }
    if (notice.link) {
      router.push(notice.link);
      // 同路由跳转不会重挂页面（如已在「我的培训」点补训通知），用事件强制刷新
      if (notice.link.startsWith('/training')) {
        window.dispatchEvent(new Event('training:refresh'));
      } else if (notice.link.startsWith('/sales')) {
        window.dispatchEvent(new Event('sales:refresh'));
      }
    }
  };

  const readAll = async () => {
    unreadSeq.current++;
    try {
      await markAllNotificationsRead();
      setUnread(0);
      setNotices(prev => prev.map(n => ({ ...n, read: true })));
    } catch { /* 忽略 */ }
  };

  const logout = () => { clearAuth(); router.push('/login'); };

  /**
   * 切换企业视角：写入 sessionStorage → 调 /auth/me 探针验证 header → 整页刷新。
   *
   * 不用 router.refresh()：App Router 不会重置页面内的 useState，
   * 旧企业的列表数据会残留在页面上。探针失败则回退视角，不把用户踢出登录。
   */
  const applyActing = async (companyId: string | null, name?: string | null) => {
    if (switchingCompany) return;
    setSwitchingCompany(true);
    setActingError('');
    setCompanyMenuOpen(false);
    setUserMenuOpen(false);
    const prevId = getActingCompanyId();
    const prevName = getActingCompanyName();
    beginActingSwitch();
    setActingCompany(companyId, name ?? null);
    try {
      await getCurrentUser();
      window.location.reload();
    } catch (e) {
      setActingCompany(prevId, prevName);
      endActingSwitch();
      setSwitchingCompany(false);
      setActingError((e as Error)?.message || '切换企业视角失败');
    }
  };

  const canSwitchCompany = permissions.includes(Permission.COMPANY_MANAGE);
  const filteredCompanies = companies.filter(c =>
    !companyKeyword.trim() || c.name.toLowerCase().includes(companyKeyword.trim().toLowerCase())
  );

  return (
    <div className="flex h-screen flex-col bg-surface" id="app-shell">
      {/* 顶栏：品牌 + 业务域 + 账号 */}
      <header className="relative z-30 flex h-14 flex-none items-center gap-2 border-b border-border bg-surface-2 px-4">
        <button
          type="button"
          onClick={() => setMobileNavOpen(v => !v)}
          className="rounded p-1.5 text-muted-foreground hover:bg-primary-light lg:hidden"
          aria-label="打开菜单"
        >
          <List size={18} />
        </button>

        <div className="mr-2 whitespace-nowrap text-[15px] font-semibold text-foreground">
          💎 AI萃取平台
        </div>

        <nav className="flex min-w-0 items-center gap-0.5 overflow-x-auto" aria-label="业务域">
          {domainsWithItems.map(d => {
            const on = d.key === activeDomain;
            const count = badges.domains[d.key] || 0;
            return (
              <button
                key={d.key}
                type="button"
                onClick={() => switchDomain(d.key)}
                aria-current={on ? 'page' : undefined}
                className={`relative flex h-8 flex-none items-center rounded-md px-3 text-[13px] transition-colors ${
                  on
                    ? 'bg-primary-light font-semibold text-primary'
                    : 'text-muted-foreground hover:bg-primary-light/60'
                }`}
              >
                {d.title}
                {count > 0 && (
                  <span
                    className="ml-1.5 h-1.5 w-1.5 rounded-full bg-red-500"
                    aria-label={`${count} 项待处理`}
                  />
                )}
              </button>
            );
          })}
        </nav>

        <div className="ml-auto flex flex-none items-center gap-2">
          <div className="hidden md:block"><ThemeToggle /></div>
          <button
            onClick={() => router.push('/portal')}
            className="hidden rounded border border-border px-3 py-1 text-xs text-muted-foreground transition-colors hover:bg-primary-light hover:text-primary sm:block"
          >
            销冠门户
          </button>

          {canSwitchCompany && (
            <div className="relative">
              <button
                type="button"
                onClick={() => { setCompanyMenuOpen(v => !v); setUserMenuOpen(false); setBellOpen(false); }}
                aria-haspopup="menu"
                aria-expanded={companyMenuOpen}
                className="flex items-center gap-1 rounded border border-border px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:bg-primary-light hover:text-primary"
              >
                {actingId ? `视角：${actingName || actingId.slice(0, 6)}` : '视角：全部企业'}
                <CaretDown size={12} />
              </button>
              {companyMenuOpen && (
                <>
                  <div className="fixed inset-0 z-40" onClick={() => setCompanyMenuOpen(false)} />
                  <div className="absolute right-0 top-full z-50 mt-1 w-64 overflow-hidden rounded-xl border border-border bg-surface shadow-xl">
                    <div className="border-b border-border p-2">
                      <input
                        value={companyKeyword}
                        onChange={e => setCompanyKeyword(e.target.value)}
                        placeholder="搜索企业"
                        className="w-full rounded-md border border-border bg-surface px-2 py-1.5 text-xs text-foreground placeholder:text-muted-foreground-2 focus:outline-none"
                      />
                    </div>
                    <div className="max-h-[320px] overflow-y-auto p-1">
                      <button
                        type="button"
                        onClick={() => applyActing(null)}
                        disabled={switchingCompany}
                        className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-xs hover:bg-primary-light disabled:opacity-50 ${
                          !actingId ? 'font-medium text-primary' : 'text-foreground'
                        }`}
                      >
                        全部企业（平台视角）
                        {!actingId && <span>✓</span>}
                      </button>
                      {companiesLoading && (
                        <div className="px-3 py-3 text-xs text-muted-foreground">企业列表加载中…</div>
                      )}
                      {!companiesLoading && companiesError && (
                        <div className="px-3 py-3 text-xs text-red-600">
                          {companiesError}
                          <button type="button" className="ml-2 underline"
                            onClick={() => setCompaniesReloadKey(k => k + 1)}>重试</button>
                        </div>
                      )}
                      {!companiesLoading && !companiesError && filteredCompanies.length === 0 && (
                        <div className="px-3 py-3 text-xs text-muted-foreground">没有匹配的企业</div>
                      )}
                      {filteredCompanies.map(c => (
                        <button
                          key={c.id}
                          type="button"
                          onClick={() => applyActing(c.id, c.name)}
                          disabled={switchingCompany}
                          className={`flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-xs hover:bg-primary-light disabled:opacity-50 ${
                            actingId === c.id ? 'font-medium text-primary' : 'text-foreground'
                          }`}
                        >
                          <span className="truncate">{c.name}</span>
                          {actingId === c.id && <span>✓</span>}
                        </button>
                      ))}
                    </div>
                    <div className="border-t border-border px-3 py-1.5 text-[10px] text-muted-foreground">
                      切换后页面会刷新
                    </div>
                  </div>
                </>
              )}
            </div>
          )}

          <div className="relative">
            <button onClick={openBell} title="通知" aria-label="通知"
              className="relative rounded-md p-1.5 text-muted-foreground hover:bg-primary-light">
              <Bell size={18} />
              {unread > 0 && (
                <span className="absolute -right-0.5 -top-0.5 min-w-[16px] rounded-full bg-red-500 px-1 text-[10px] leading-4 text-white">
                  {unread > 99 ? '99+' : unread}
                </span>
              )}
            </button>
            {bellOpen && (
              <>
                <div className="fixed inset-0 z-40" onClick={() => setBellOpen(false)} />
                <div className="absolute right-0 top-full z-50 mt-1 w-[340px] max-w-[calc(100vw-2rem)] rounded-xl border border-border bg-surface shadow-xl">
                  <div className="flex items-center justify-between border-b border-border px-4 py-2.5">
                    <span className="text-sm font-medium text-foreground">通知</span>
                    <button onClick={readAll} className="text-xs text-muted-foreground hover:underline">
                      全部已读
                    </button>
                  </div>
                  <div className="max-h-[380px] overflow-y-auto">
                    {notices.length === 0 && !bellLoading && (
                      bellError ? (
                        <div className="px-4 py-8 text-center text-xs text-red-600">
                          {bellError}
                          <button onClick={openBell} className="ml-2 underline">重试</button>
                        </div>
                      ) : (
                        <div className="px-4 py-8 text-center text-xs text-muted-foreground">暂无通知</div>
                      )
                    )}
                    {notices.map(n => (
                      <button key={n.id} onClick={() => openNotice(n)}
                        className={`block w-full border-b border-border px-4 py-3 text-left hover:bg-primary-light/50 ${
                          n.read ? 'opacity-70' : ''}`}>
                        <div className="flex items-center gap-2">
                          {!n.read && <span className="h-1.5 w-1.5 rounded-full bg-primary" />}
                          <span className="text-sm font-medium text-foreground">{n.title}</span>
                        </div>
                        {n.content && (
                          <div className="mt-1 line-clamp-2 text-xs text-muted-foreground">{n.content}</div>
                        )}
                        <div className="mt-1 text-[10px] text-muted-foreground">
                          {new Date(n.createdAt).toLocaleString()}
                        </div>
                      </button>
                    ))}
                    {bellPage < bellTotalPages && (
                      <button onClick={loadMoreNotices} disabled={bellLoading}
                        className="w-full px-4 py-2.5 text-center text-xs text-primary hover:underline disabled:opacity-50">
                        {bellLoading ? '加载中…' : '加载更多'}
                      </button>
                    )}
                  </div>
                </div>
              </>
            )}
          </div>

          <div className="relative">
            <button
              type="button"
              onClick={() => { setUserMenuOpen(v => !v); setBellOpen(false); }}
              aria-haspopup="menu"
              aria-expanded={userMenuOpen}
              aria-label="账号菜单"
              className="flex h-8 w-8 items-center justify-center rounded-full bg-gradient-to-br from-cyan-500 to-blue-500 text-xs font-semibold text-white"
            >
              {user?.name?.slice(0, 1) || '·'}
            </button>
            {userMenuOpen && (
              <>
                <div className="fixed inset-0 z-40" onClick={() => setUserMenuOpen(false)} />
                <div className="absolute right-0 top-full z-50 mt-1 w-52 overflow-hidden rounded-xl border border-border bg-surface shadow-xl" role="menu">
                  <div className="border-b border-border px-4 py-3">
                    <div className="text-sm font-medium text-foreground">{user?.name}</div>
                    <div className="mt-0.5 text-[11px] text-muted-foreground">
                      {roleLabel(permissions)}{user?.companyName ? ` · ${user.companyName}` : ''}
                    </div>
                    {actingId && (
                      <div className="mt-0.5 text-[11px] text-amber-700">
                        当前视角：{actingName || actingId.slice(0, 8)}
                      </div>
                    )}
                  </div>
                  <button
                    role="menuitem"
                    onClick={() => { setUserMenuOpen(false); router.push('/space/me'); }}
                    className="flex w-full items-center gap-2 px-4 py-2.5 text-left text-[13px] text-foreground hover:bg-primary-light/50"
                  >
                    <UserCircle size={15} /> 我的空间
                  </button>
                  <button
                    role="menuitem"
                    onClick={() => { setUserMenuOpen(false); router.push('/help'); }}
                    className="flex w-full items-center gap-2 px-4 py-2.5 text-left text-[13px] text-foreground hover:bg-primary-light/50"
                  >
                    <Question size={15} /> 使用帮助
                  </button>
                  <div className="border-t border-border" />
                  <button
                    role="menuitem"
                    onClick={logout}
                    className="flex w-full items-center gap-2 px-4 py-2.5 text-left text-[13px] text-red-600 hover:bg-red-50"
                  >
                    <SignOut size={15} /> 退出登录
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      </header>

      {/* 超管切换企业后的视角提示：滚动长页面时也能看到自己在谁的数据里 */}
      {actingId && (
        <div className="flex flex-none items-center justify-center gap-2 border-b border-amber-200 bg-amber-50 px-4 py-1 text-xs text-amber-800">
          <span>
            当前视角：<strong>{actingName || actingId.slice(0, 8)}</strong>
            {user?.companyName ? ` · 原企业：${user.companyName}` : ''}
          </span>
          <button type="button" onClick={() => applyActing(null)} disabled={switchingCompany}
            className="underline disabled:opacity-50">
            {switchingCompany ? '切换中…' : '退出视角'}
          </button>
        </div>
      )}
      {actingError && (
        <div className="flex flex-none items-center justify-center gap-2 border-b border-red-200 bg-red-50 px-4 py-1 text-xs text-red-600">
          {actingError}
          <button type="button" onClick={() => setActingError('')} className="underline">关闭</button>
        </div>
      )}

      <div className="flex min-h-0 flex-1">
        {mobileNavOpen && (
          <div
            className="fixed inset-0 z-40 bg-slate-900/30 lg:hidden"
            onClick={() => setMobileNavOpen(false)}
          />
        )}

        {/* 侧栏：当前域 + 底部帮助与学习 */}
        <aside
          className={`${
            mobileNavOpen ? 'fixed inset-y-0 left-0 z-50 flex' : 'hidden'
          } w-[220px] flex-none flex-col border-r border-border bg-surface-2 lg:static lg:flex`}
        >
          <div className="px-4 pb-2 pt-5 text-[11px] font-semibold tracking-wider text-muted-foreground">
            {activeTitle}
          </div>
          <nav className="flex-1 overflow-y-auto px-2.5 pb-3" aria-label={`${activeTitle}菜单`}>
            {activeItems.map(item => {
              const on = item.path === activeItemPath;
              const count = badges.items[item.path] || 0;
              return (
                <button
                  key={item.path + item.label}
                  onClick={() => pathname === item.path ? router.refresh() : router.push(item.path)}
                  aria-current={on ? 'page' : undefined}
                  className={`mb-0.5 flex h-9 w-full items-center gap-2.5 rounded-md px-2.5 text-left text-[13px] transition-colors ${
                    on
                      ? 'bg-primary-light font-semibold text-primary'
                      : 'text-muted-foreground hover:bg-primary-light/60'
                  }`}
                >
                  <NavIcon
                    label={item.label}
                    className={on ? 'text-primary' : 'text-muted-foreground'}
                  />
                  <span className="flex-1 truncate">{item.label}</span>
                  {count > 0 && (
                    <span className="rounded-md bg-red-50 px-1.5 py-0.5 text-[10px] font-semibold leading-none text-red-600">
                      {count > 99 ? '99+' : count}
                    </span>
                  )}
                </button>
              );
            })}
          </nav>

          <HelpMenu onOpenPalette={() => setPaletteOpen(true)} />
        </aside>

        <main className="min-w-0 flex-1 overflow-auto">
          {children}
        </main>
      </div>

      <CommandPalette
        open={paletteOpen}
        onClose={() => setPaletteOpen(false)}
        items={visibleNav}
      />
    </div>
  );
}
