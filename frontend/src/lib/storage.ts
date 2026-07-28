/**
 * SSR-safe 浏览器存储工具
 *
 * 集中管理所有 localStorage 读写，统一 key 命名，提供 SSR 安全保护。
 * 页面和 API 模块统一从此导入，不再直接调用 localStorage。
 *
 * @since 2026-07-01
 */

const KEYS = {
  TOKEN: 'token',
  USER: 'user',
} as const;

// ==================== 底层 SSR-safe 读写 ====================

function isBrowser(): boolean {
  return typeof window !== 'undefined';
}

function safeGetItem(key: string): string | null {
  if (!isBrowser()) return null;
  return localStorage.getItem(key);
}

function safeSetItem(key: string, value: string): void {
  if (!isBrowser()) return;
  localStorage.setItem(key, value);
}

function safeRemoveItem(key: string): void {
  if (!isBrowser()) return;
  localStorage.removeItem(key);
}

// ==================== Token & User ====================

/** 获取 JWT token（SSR 安全，服务端返回 null）。B端 token 优先，fallback C端 c_auth */
export function getToken(): string | null {
  const bToken = safeGetItem(KEYS.TOKEN);
  if (bToken) return bToken;
  // C 端 Bearer token（分享页/注册/登录写入 localStorage.c_auth）
  const cAuth = safeGetItem('c_auth');
  if (cAuth) {
    try {
      const session = JSON.parse(cAuth);
      return session?.token || null;
    } catch { return null; }
  }
  return null;
}

/** 获取当前登录用户信息 */
export function getUser(): { name: string; role: string; [key: string]: unknown } | null {
  const raw = safeGetItem(KEYS.USER);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

/** 登录成功时写入 token 和用户信息 */
export function setAuth(token: string, user: Record<string, unknown>): void {
  safeSetItem(KEYS.TOKEN, token);
  safeSetItem(KEYS.USER, JSON.stringify(user));
}

/** 退出登录或 token 过期时清除 */
export function clearAuth(): void {
  safeRemoveItem(KEYS.TOKEN);
  safeRemoveItem(KEYS.USER);
  safeRemoveItem('c_auth');
}

// ==================== 报告检查清单 ====================

function checklistKey(reportId: string): string {
  return `checklist-${reportId}`;
}

export function getChecklist(reportId: string): Record<string, unknown> | null {
  const raw = safeGetItem(checklistKey(reportId));
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export function setChecklist(reportId: string, state: Record<string, unknown>): void {
  safeSetItem(checklistKey(reportId), JSON.stringify(state));
}
