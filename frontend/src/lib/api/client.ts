/**
 * 统一 API 请求客户端
 *
 * 自动注入 Authorization header，统一处理响应格式和错误。
 * 所有 API 模块通过此客户端发请求，不再各自实现 apiRequest。
 *
 * @since 2026-07-01
 */

import { clearAuth } from '@/lib/storage';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL || '/api/v1';
export { API_BASE };

/**
 * 通用 JSON API 请求
 *
 * JWT 通过 HttpOnly Cookie 自动发送，无需手动管理 token。
 * - 同域部署：Cookie 自动附带
 * - 跨域部署：需设置 credentials: 'include'
 */
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
    credentials: 'include', // 发送 HttpOnly Cookie
  });

  if (!res.ok) {
    if ((res.status === 401 || res.status === 403) && typeof window !== 'undefined') {
      // C 端分享页：不跳 B 端登录、不清 B 端凭证，派发事件由页面弹注册/登录抽屉
      if (window.location.pathname.startsWith('/s/')) {
        window.dispatchEvent(new CustomEvent('guest:auth-required'));
        throw new Error('AUTH_REQUIRED');
      }
      clearAuth();
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

/** 请求头快捷方法（向后兼容，Cookie 自动携带） */
export function authHeaders(extra?: Record<string, string>): Record<string, string> {
  return { ...extra };
}
