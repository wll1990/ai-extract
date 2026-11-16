/**
 * 导航域与路径归属。
 *
 * 菜单项本身由后端 /config/nav 下发（含 domain）；这里只维护域的顺序/标题，
 * 以及「非菜单路径」到域的映射（详情页、子页、导览深链）。
 *
 * @since 2026-11-02
 */

export type NavDomainKey =
  | 'daily'
  | 'sales'
  | 'production'
  | 'knowledge'
  | 'manage'
  | 'platform';

export interface NavItem {
  path: string;
  label: string;
  permission: string | null;
  domain: NavDomainKey;
  /** 仅本地回退菜单使用：同一路径在不同角色下归属不同域（如上传素材） */
  roles?: string[];
}

export interface NavBadges {
  domains: Partial<Record<NavDomainKey, number>>;
  items: Record<string, number>;
}

export const DOMAINS: ReadonlyArray<{ key: NavDomainKey; title: string }> = [
  { key: 'daily', title: '日常' },
  { key: 'sales', title: '销售' },
  { key: 'production', title: '分身生产' },
  { key: 'knowledge', title: '知识' },
  { key: 'manage', title: '管理' },
  { key: 'platform', title: '平台' },
];

export const DOMAIN_TITLE: Record<NavDomainKey, string> = DOMAINS.reduce(
  (acc, d) => { acc[d.key] = d.title; return acc; },
  {} as Record<NavDomainKey, string>,
);

export function isNavDomainKey(v: unknown): v is NavDomainKey {
  return typeof v === 'string' && v in DOMAIN_TITLE;
}

/**
 * 非菜单路径 → 域。比菜单前缀更具体，必须按前缀长度倒序匹配。
 * 例：/admin/training/cohorts 属于「我的培训」，不能落到 /admin。
 */
const SPECIAL_PREFIX_LIST: Array<[string, NavDomainKey]> = [
  ['/admin/training/cohorts', 'sales'],
  ['/admin/skills/', 'production'],
  ['/admin/insights/', 'knowledge'],
  ['/admin/grains/', 'production'],
  ['/admin/knowledge/', 'knowledge'],
  ['/admin/cases/', 'knowledge'],
  ['/admin/evaluations/', 'knowledge'],
  ['/skill/', 'daily'],
  ['/space/', 'daily'],
  ['/sales/', 'sales'],
  ['/training/', 'sales'],
];

const SPECIAL_PREFIXES = [...SPECIAL_PREFIX_LIST].sort((a, b) => b[0].length - a[0].length);

/**
 * 按当前路径反推所属域：菜单精确匹配 → 特殊前缀 → 最长菜单前缀。
 * 返回 null 表示该页不属于任何域（如 /help），调用方保留上一次的域。
 */
export function resolveDomain(pathname: string, items: NavItem[]): NavDomainKey | null {
  const exact = items.find(i => i.path === pathname);
  if (exact) return exact.domain;

  for (const [prefix, domain] of SPECIAL_PREFIXES) {
    if (pathname.startsWith(prefix)) return domain;
  }

  let best: NavItem | null = null;
  for (const item of items) {
    if (pathname.startsWith(item.path + '/')) {
      if (!best || item.path.length > best.path.length) best = item;
    }
  }
  return best?.domain ?? null;
}

/** 当前路径是否命中该菜单项（含详情页与别名路径） */
export function isNavActive(currentPath: string, navPath: string): boolean {
  if (currentPath === navPath) return true;
  // 「我的空间」跳到 /space/{id} 后仍高亮
  if (navPath === '/space/me') {
    return currentPath.startsWith('/space/')
      && currentPath !== '/spaces'
      && !currentPath.startsWith('/spaces/');
  }
  // 我的培训：/training/... 与导师查看的批次详情都算同一入口
  if (navPath === '/training') {
    return currentPath.startsWith('/training/') || currentPath.startsWith('/admin/training/cohorts');
  }
  // 培训计划：批次详情归属「我的培训」，其余 /admin/training 都算计划管理
  if (navPath === '/admin/training/plans') {
    return currentPath.startsWith('/admin/training')
      && !currentPath.startsWith('/admin/training/cohorts');
  }
  const depth = navPath.split('/').filter(Boolean).length;
  if (depth === 1) return false;
  return currentPath.startsWith(navPath + '/');
}
