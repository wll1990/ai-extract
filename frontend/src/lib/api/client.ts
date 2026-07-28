/**
 * 统一 API 请求客户端
 *
 * 自动注入 Authorization header，统一处理响应格式和错误。
 * 所有 API 模块通过此客户端发请求，不再各自实现 apiRequest。
 *
 * @since 2026-07-01
 */

import { clearAuth, getToken } from '@/lib/storage';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL || '/api/v1';
export { API_BASE };

/**
 * 通用 JSON API 请求
 *
 * 优先走 HttpOnly Cookie（B端），降级 Bearer token（C端 c_auth localStorage）。
 * - 同域部署：Cookie 自动附带
 * - 跨域部署 / C端：显式传 Authorization header
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

  // C端 Bearer token 降级（B端 Cookie 不存在时）
  if (!headers['Authorization']) {
    const token = getToken();
    if (token) headers['Authorization'] = `Bearer ${token}`;
  }

  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
    credentials: 'include', // 发送 HttpOnly Cookie（B端）
  });

  if (!res.ok) {
    if ((res.status === 401 || res.status === 403) && typeof window !== 'undefined') {
      const path = window.location.pathname;
      // C 端分享页：派发事件由页面弹注册/登录抽屉
      if (path.startsWith('/s/')) {
        window.dispatchEvent(new CustomEvent('guest:auth-required'));
        throw new Error('AUTH_REQUIRED');
      }
      // H5 页面：不跳 B 端登录，只清凭证
      if (path.startsWith('/h5/') || path.startsWith('/i/')) {
        clearAuth();
        throw new Error('请先登录');
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
