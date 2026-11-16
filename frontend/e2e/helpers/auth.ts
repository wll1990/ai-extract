import { APIRequestContext, Browser, BrowserContext, Page } from '@playwright/test';

export const API_URL = process.env.E2E_API_URL || 'http://localhost:8080/api/v1';
export const COMPANY_ID =
  process.env.E2E_COMPANY_ID || '10000000-0000-0000-0000-000000000001';
export const COMPANY_CODE = process.env.E2E_COMPANY_CODE;

export interface Session {
  token: string;
  user: Record<string, unknown>;
}

export async function loginViaApi(
  request: APIRequestContext,
  account: string,
  password: string,
): Promise<Session> {
  const res = await request.post(`${API_URL}/auth/login`, {
    data: COMPANY_CODE
      ? { companyCode: COMPANY_CODE, account, password }
      : { companyId: COMPANY_ID, account, password },
  });
  if (!res.ok()) {
    throw new Error(`登录失败 ${account}: ${res.status()} ${await res.text()}`);
  }
  const body = await res.json();
  if (body.code !== 200 || !body.data?.token) {
    throw new Error(`登录失败 ${account}: ${JSON.stringify(body)}`);
  }
  return { token: body.data.token, user: body.data.user };
}

export async function newLoggedInPage(
  browser: Browser,
  session: Session,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext();
  // 后端登录会下发 HttpOnly cookie；脱离 test runner 的 storageState 时也要显式注入
  await context.addCookies([{
    name: 'token',
    value: session.token,
    url: process.env.E2E_BASE_URL || 'http://localhost:3000',
    httpOnly: true,
    sameSite: 'Strict',
  }]);
  await context.addInitScript(
    ({ token, user }) => {
      localStorage.setItem('token', token);
      localStorage.setItem('user', JSON.stringify(user));
    },
    { token: session.token, user: session.user },
  );
  const page = await context.newPage();
  return { context, page };
}
