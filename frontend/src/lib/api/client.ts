/**
 * 统一 API 请求客户端
 *
 * 自动注入 Authorization header，统一处理响应格式和错误。
 * 所有 API 模块通过此客户端发请求，不再各自实现 apiRequest。
 *
 * @since 2026-07-01
 */

import { clearAuth, getToken } from '@/lib/storage';
import { csrfHeader, isUnsafeMethod } from '@/lib/csrf';
import { getActingHeaders, isActingSwitching } from '@/lib/acting-company';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL || '/api/v1';
export { API_BASE };

/** 带 HTTP 状态与业务子码的 API 错误（subCode 由后端 409 冲突下发） */
export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code: number,
    public readonly subCode?: string,
    /** 后端 X-Trace-Id，排查问题时带上它可直接定位日志 */
    public readonly traceId?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/**
 * 通用 JSON API 请求
 *
 * 同域部署时浏览器会自动附带 Cookie；跨域部署或 C 端再用 localStorage 里的
 * Bearer token 兜底（后端 JwtAuthFilter 优先读 Authorization header）。
 */
export interface ApiOptions extends RequestInit {
  /**
   * 可选请求（如场景标签、推荐问题）失败时只抛错，不清凭证、不跳登录。
   * 必需请求保持默认 false，401 仍然统一回登录页。
   */
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

  // 调用方可能把空 token 拼成 `Bearer undefined/null`；这种头会顶掉 Cookie
  // 认证并被后端判 401。统一当作未提供，走下面的 Cookie / token 兜底。
  if (headers['Authorization'] && /^Bearer\s*(?:undefined|null)?$/i.test(headers['Authorization'].trim())) {
    delete headers['Authorization'];
  }

  // C端 Bearer token 降级（B端 Cookie 认证时后端仍兼容 Bearer）
  if (!headers['Authorization']) {
    const token = getToken();
    if (token) headers['Authorization'] = `Bearer ${token}`;
  }

  // Cookie 认证的写请求需要 CSRF 双提交；Bearer 场景后端不校验
  if (isUnsafeMethod(options.method) && !headers['X-CSRF-Token']) {
    Object.assign(headers, csrfHeader());
  }

  // 超管切换企业视角：普通用户 getActingHeaders 恒为空
  if (!headers['X-Acting-Company-Id']) {
    Object.assign(headers, getActingHeaders());
  }

  // OBS-02：前端生成 traceId，Nginx 访问日志与后端日志可直接按它串联
  if (!headers['X-Trace-Id']) {
    headers['X-Trace-Id'] = `web-${crypto.randomUUID()}`;
  }

  const res = await fetch(`${API_BASE}${path}`, {
    credentials: 'same-origin',
    ...requestInit,
    headers,
  });

  if (!res.ok) {
    if (res.status === 401 && isActingSwitching()) {
      // 切换视角的探针失败：交给切换流程回退，不能把用户踢出登录
      throw new ApiError('切换企业视角失败', res.status, res.status);
    }
    // 可选请求失败（例如场景标签）：交给调用方降级，不把整个会话踢掉
    if (res.status === 401 && skipAuthRedirect) {
      throw new ApiError('登录已过期', res.status, res.status);
    }
    if (res.status === 401 && typeof window !== 'undefined') {
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
    let code = res.status;
    let subCode: string | undefined;
    try {
      const errBody = await res.json();
      if (errBody.message) msg = errBody.message;
      if (typeof errBody.code === 'number') code = errBody.code;
      if (typeof errBody.subCode === 'string') subCode = errBody.subCode;
    } catch { /* ignore */ }
    throw new ApiError(msg, res.status, code, subCode,
      res.headers.get('X-Trace-Id') || undefined);
  }

  const json = await res.json();
  if (json.code !== 200) {
    throw new ApiError(json.message || '请求失败', res.status, json.code, json.subCode);
  }
  return json.data as T;
}

/**
 * 请求头快捷方法：有 Bearer token（如 C 端 localStorage）时才加 Authorization，
 * B 端只走 HttpOnly Cookie，避免拼出 `Bearer null` 把 cookie 认证顶掉。
 */
export function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = { ...extra };
  const token = getToken();
  if (token && !headers['Authorization']) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  return headers;
}
