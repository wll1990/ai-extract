/**
 * 认证 API 客户端 — @synced-from frontend/src/lib/api/auth.ts
 *
 * @since 2026-07-20
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

export function login(params: LoginParams): Promise<LoginResult> {
  return apiClient('/auth/login', { method: 'POST', body: JSON.stringify(params) });
}

export interface RegisterParams {
  companyId: string;
  name: string;
  account: string;
  password: string;
  role: string;
}

export function register(params: RegisterParams): Promise<LoginResult> {
  return apiClient('/auth/register', { method: 'POST', body: JSON.stringify(params) });
}

export function logout(): Promise<void> {
  return apiClient('/auth/logout', { method: 'POST' });
}

export function getCurrentUser(): Promise<UserInfo> {
  return apiClient<UserInfo>('/auth/me');
}
