/**
 * 统一 API 请求客户端 — @synced-from frontend/src/lib/api/client.ts
 *
 * 差异：401/403 跳转 /login（企业端无 /s/ 分享页逻辑）。
 * JWT 通过 HttpOnly Cookie 自动发送。
 *
 * @since 2026-07-20
 */

function resolveBaseUrl(): string {
  const base = process.env.NEXT_PUBLIC_API_BASE_URL || '/api/v1';

  // 已是绝对 URL — 服务端和客户端都能直接使用
  if (base.startsWith('http://') || base.startsWith('https://')) {
    return base;
  }

  // 相对 URL 在浏览器端正常工作（浏览器 fetch 基于 window.location.origin 解析）
  if (typeof window !== 'undefined') {
    return base;
  }

  // 服务端 + 相对 URL — Node.js undici fetch 无法解析相对路径
  // 使用显式的后端 origin 兜底，默认指向本地开发环境
  const origin =
    process.env.API_BACKEND_ORIGIN ||
    process.env.NEXT_PUBLIC_API_BASE_URL_ORIGIN ||
    'http://localhost:8080';

  console.warn(
    `[apiClient] 检测到服务端使用相对 API_BASE ("${base}")，` +
    `自动拼接 origin "${origin}"。` +
    `建议将 NEXT_PUBLIC_API_BASE_URL 设为绝对 URL（如 "${origin}${base}"）以消除此警告。`,
  );

  return `${origin}${base}`;
}

const API_BASE = resolveBaseUrl();
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
