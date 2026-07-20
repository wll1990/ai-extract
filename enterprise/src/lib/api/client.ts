/**
 * 统一 API 请求客户端 — @synced-from frontend/src/lib/api/client.ts
 *
 * 差异：401/403 跳转 /login（企业端无 /s/ 分享页逻辑）。
 * JWT 通过 HttpOnly Cookie 自动发送。
 *
 * @since 2026-07-20
 */

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL || '/api/v1';
export { API_BASE };

export async function apiClient<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers: Record<string, string> = {};

  if (!(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
  }

  if (options.headers) {
    Object.assign(headers, options.headers as Record<string, string>);
  }

  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
    credentials: 'include',
  });

  if (!res.ok) {
    if ((res.status === 401 || res.status === 403) && typeof window !== 'undefined') {
      window.location.href = '/login';
      throw new Error('登录已过期，请重新登录');
    }
    let msg = `请求失败 (${res.status})`;
    try {
      const errBody = await res.json();
      if (errBody.message) msg = errBody.message;
    } catch { /* ignore */ }
    throw new Error(msg);
  }

  const json = await res.json();
  if (json.code !== 200) {
    throw new Error(json.message || '请求失败');
  }
  return json.data as T;
}
