/**
 * SSE 鉴权工具 — H5 C端 Bearer token vs B端 cookie 统一处理。
 *
 * EventSource 不支持自定义 header，只能通过 URL query param 传 token。
 * JwtAuthFilter.extractToken() 已支持从 Cookie/Header/query param 三种来源读 token。
 */

const C_AUTH_KEY = 'c_auth';

/** 从 localStorage 读取 C 端 token */
function getCToken(): string | null {
  if (typeof window === 'undefined') return null;
  try {
    const stored = localStorage.getItem(C_AUTH_KEY);
    if (!stored) return null;
    const session = JSON.parse(stored);
    return session?.token || null;
  } catch {
    return null;
  }
}

/** 从 Cookie 读取 B 端 token */
function getBToken(): string | null {
  if (typeof document === 'undefined') return null;
  const match = document.cookie.match(/(?:^|;\s*)token=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : null;
}

/**
 * 返回可拼接到 URL 的鉴权参数。
 * SSE EventSource / fetch 调用前统一调用此函数。
 *
 * @returns "?token=xxx" 或 ""
 */
export function getSSEAuthParams(): string {
  const cToken = getCToken();
  if (cToken) return `?token=${encodeURIComponent(cToken)}`;
  const bToken = getBToken();
  if (bToken) return `?token=${encodeURIComponent(bToken)}`;
  return '';
}

/**
 * 返回 fetch 用的 headers。
 * 适用于支持自定义 header 的非 SSE 请求。
 */
export function getAuthHeaders(): Record<string, string> {
  const cToken = getCToken();
  if (cToken) return { Authorization: `Bearer ${cToken}` };
  return {};
}
