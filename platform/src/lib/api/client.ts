/**
 * 统一 API 请求客户端 — @synced-from frontend/src/lib/api/client.ts
 *
 * 差异：401/403 跳转 /login（企业端无 /s/ 分享页逻辑）。
 * Bearer 优先（localStorage c_auth），API 调用唯一认证通道。
 *
 * @since 2026-07-20
 */

import { clearAuth, getToken } from '@/lib/storage';
import { csrfHeader, isUnsafeMethod } from '@/lib/csrf';

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

export interface ApiOptions extends RequestInit {
  /** 可选请求 401 时只抛错，不清凭证、不跳登录 */
  skipAuthRedirect?: boolean;
}

export async function apiClient<T>(
  path: string,
  options: ApiOptions = {},
): Promise<T> {
  const { skipAuthRedirect, ...requestInit } = options;
  const headers: Record<string, string> = {};

  if (!(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
  }

  if (options.headers) {
    Object.assign(headers, options.headers as Record<string, string>);
  }

  // 调用方把空 token 拼成 `Bearer undefined/null/空` 时，会顶掉 Cookie 认证。
  if (headers['Authorization'] && /^Bearer\s*(?:undefined|null)?$/i.test(headers['Authorization'].trim())) {
    delete headers['Authorization'];
  }

  // C 端 Bearer token（localStorage c_auth），无显式 Authorization 时自动补齐
  if (!headers['Authorization']) {
    const token = getToken();
    if (token) headers['Authorization'] = `Bearer ${token}`;
  }

  // Cookie 认证的写请求需要 CSRF 双提交；Bearer 场景后端不校验
  if (isUnsafeMethod(options.method) && !headers['X-CSRF-Token']) {
    Object.assign(headers, csrfHeader());
  }

  // OBS-02：前端生成 traceId，Nginx / 后端日志按它串联
  if (!headers['X-Trace-Id']) {
    headers['X-Trace-Id'] = `web-${crypto.randomUUID()}`;
  }

  const res = await fetch(`${API_BASE}${path}`, {
    credentials: 'same-origin',
    ...requestInit,
    headers,
  });

  if (!res.ok) {
    if (res.status === 401 && skipAuthRedirect) {
      throw new Error('登录已过期');
    }
    if (res.status === 401 && typeof window !== 'undefined') {
      const path = window.location.pathname;
      // C 端分享页：派发事件由页面弹注册/登录抽屉
      if (path.startsWith('/s/')) {
        window.dispatchEvent(new CustomEvent('guest:auth-required'));
        throw new Error('AUTH_REQUIRED');
      }
      // H5 页面：不跳登录，只清凭证
      if (path.startsWith('/h5/')) {
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

/**
 * 请求头快捷方法。
 * 仅透传 extra 参数，不注入 Bearer token——B 端走 HttpOnly Cookie 认证。
 * C 端 Bearer 认证请用 skill.ts 中的 bearer() helper。
 */
export function authHeaders(extra?: Record<string, string>): Record<string, string> {
  return { ...extra };
}
