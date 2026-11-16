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
    /** true=管理员重置/首次登录，必须先修改密码 */
    mustChangePassword?: boolean;
  };
}

export interface UserInfo {
  id: string; name: string; role: string;
  avatarUrl: string | null; companyId: string; companyName: string;
  permissions: string[];
  /** true=管理员重置/首次登录，必须先修改密码 */
  mustChangePassword?: boolean;
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

/** 修改自己的密码（成功后服务端下发新 token） */
export function changePassword(oldPassword: string, newPassword: string): Promise<{ token: string }> {
  return apiClient<{ token: string }>('/auth/change-password', {
    method: 'POST',
    body: JSON.stringify({ oldPassword, newPassword }),
  });
}
