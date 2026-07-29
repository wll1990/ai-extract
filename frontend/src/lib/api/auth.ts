/**
 * 认证 API 客户端
 * @since 2026-07-01
 */

import { apiClient } from './client';

export interface LoginParams {
  companyCode?: string;
  companyId?: string;
  account: string;
  password: string;
}

export interface LoginResult {
  token: string;
  user: {
    id: string; name: string; role: string;
    avatarUrl: string | null; companyId: string; companyName: string;
    permissions: string[];
  };
}

export interface UserInfo {
  id: string; name: string; role: string;
  avatarUrl: string | null; companyId: string; companyName: string;
  permissions: string[];
}

export interface RegisterParams {
  companyId: string;
  name: string;
  account: string;
  password: string;
  role: string;
}

/** 登录 */
export function login(params: LoginParams): Promise<LoginResult> {
  return apiClient<LoginResult>('/auth/login', { method: 'POST', body: JSON.stringify(params) });
}

/** 注册 */
export function register(params: RegisterParams): Promise<LoginResult> {
  return apiClient<LoginResult>('/auth/register', { method: 'POST', body: JSON.stringify(params) });
}

/** 获取当前用户 */
export function getCurrentUser(): Promise<UserInfo> {
  return apiClient<UserInfo>('/auth/me');
}
