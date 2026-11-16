/**
 * CSRF 双提交辅助 — 后端对 Cookie 认证的写请求校验 X-CSRF-Token。
 *
 * csrf_token 是非 HttpOnly Cookie，由 /auth/login、/auth/register 下发，
 * 前端读取后回写到请求头即可；Bearer 调用不需要（后端只校验 Cookie 认证）。
 */
export function getCsrfToken(): string | null {
  if (typeof document === 'undefined') return null;
  const match = document.cookie.match(/(?:^|;\s*)csrf_token=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : null;
}

export function csrfHeader(): Record<string, string> {
  const token = getCsrfToken();
  return token ? { 'X-CSRF-Token': token } : {};
}

export function isUnsafeMethod(method?: string): boolean {
  const m = (method || 'GET').toUpperCase();
  return m !== 'GET' && m !== 'HEAD' && m !== 'OPTIONS';
}
