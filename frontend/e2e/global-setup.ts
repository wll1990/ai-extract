import { request, type FullConfig } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

/**
 * 登录一次并保存 storageState。
 *
 * JwtAuthFilter 只读 Authorization header（query token 仅 SSE 白名单），
 * 所以必须同时注入 Cookie（Next middleware 放行）与 localStorage.token（apiClient 使用）。
 */
export default async function globalSetup(_config: FullConfig) {
  const baseURL = process.env.E2E_BASE_URL || 'http://localhost:3000';
  const apiURL = process.env.E2E_API_URL || 'http://localhost:8080/api/v1';
  const account = process.env.E2E_ACCOUNT || 'admin';
  const password = process.env.E2E_PASSWORD || '123456';
  const companyId = process.env.E2E_COMPANY_ID || 'c0000000-0000-0000-0000-000000000001';
  const companyCode = process.env.E2E_COMPANY_CODE;

  const api = await request.newContext();
  const login = await api.post(`${apiURL}/auth/login`, {
    data: companyCode ? { account, password, companyCode } : { account, password, companyId },
  });
  if (!login.ok()) {
    throw new Error(`E2E 登录失败：HTTP ${login.status()} ${await login.text()}`);
  }
  const body = await login.json();
  const token: string = body?.data?.token;
  if (!token) {
    throw new Error('E2E 登录失败：响应中没有 token');
  }

  let user: unknown = body?.data?.user ?? null;
  try {
    const me = await api.get(`${apiURL}/auth/me`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (me.ok()) {
      user = (await me.json())?.data ?? user;
    }
  } catch {
    // header 鉴权失败时仍可用 token 继续跑页面级冒烟
  }
  await api.dispose();

  const target = new URL(baseURL);
  const cookieDomain = target.hostname;
  const cookieSecure = target.protocol === 'https:';
  const dir = path.join(__dirname, '.auth');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'admin.json'), JSON.stringify({
    cookies: [{
      name: 'token',
      value: token,
      domain: cookieDomain,
      path: '/',
      expires: -1,
      httpOnly: true,
      secure: cookieSecure,
      sameSite: 'Strict',
    }],
    origins: [{
      origin: baseURL,
      localStorage: [
        { name: 'token', value: token },
        ...(user ? [{ name: 'user', value: JSON.stringify(user) }] : []),
      ],
    }],
  }, null, 2));
}
