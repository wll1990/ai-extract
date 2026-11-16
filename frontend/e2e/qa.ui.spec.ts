import { test, expect, request, type Browser } from '@playwright/test';
import { loginViaApi, newLoggedInPage } from './helpers/auth';

/**
 * 线上 QA 租户的 UI 冒烟：只验证「能打开、菜单对、权限拒绝不白屏」。
 * 账号与域名由 scripts/qa/run.mjs 通过 E2E_* 注入。
 */

const PASSWORD = process.env.E2E_PASSWORD || '';

async function pageAs(browser: Browser, account: string) {
  const api = await request.newContext();
  const session = await loginViaApi(api, account, PASSWORD);
  await api.dispose();
  const { page, context } = await newLoggedInPage(browser, session);
  return { page, context };
}

test('QA 管理员：五个域可见，平台域不可见', async ({ browser }) => {
  const { page, context } = await pageAs(browser, process.env.E2E_ACCOUNT || 'qa_admin');
  await page.goto('/');
  for (const title of ['日常', '销售', '分身生产', '知识', '管理']) {
    await expect(page.getByRole('button', { name: title }).first()).toBeVisible();
  }
  await expect(page.getByRole('button', { name: '平台' })).toHaveCount(0);
  await context.close();
});

test('QA 管理员：知识库与评测中心可打开', async ({ browser }) => {
  const { page, context } = await pageAs(browser, process.env.E2E_ACCOUNT || 'qa_admin');
  await page.goto('/admin/knowledge');
  await expect(page.getByText('知识库').first()).toBeVisible();
  await page.goto('/admin/evaluations');
  await expect(page.getByText('评测中心').first()).toBeVisible();
  await context.close();
});

test('QA 员工：只有日常和销售域', async ({ browser }) => {
  const { page, context } = await pageAs(browser, process.env.E2E_EMP || 'qa_emp');
  await page.goto('/');
  await expect(page.getByRole('button', { name: '日常' }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: '销售' }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: '管理' })).toHaveCount(0);
  await context.close();
});

test('QA 员工：直接访问管理页被拦截', async ({ browser }) => {
  const { page, context } = await pageAs(browser, process.env.E2E_EMP || 'qa_emp');
  await page.goto('/admin/users');
  await page.waitForTimeout(2000);
  expect(page.url()).not.toContain('/admin/users');
  await context.close();
});

test('QA 管理员：团队共性问题可打开并显示聚合或空状态', async ({ browser }) => {
  const { page, context } = await pageAs(browser, process.env.E2E_ACCOUNT || 'qa_admin');
  await page.goto('/sales/insights');
  await expect(page.getByRole('heading', { name: '团队共性问题' })).toBeVisible();
  await expect(page.getByText(/已分析|还没有足够的已完成报告/).first()).toBeVisible();
  await context.close();
});

test('QA 员工：看不到团队共性问题，直接访问提示无权限', async ({ browser }) => {
  const { page, context } = await pageAs(browser, process.env.E2E_EMP || 'qa_emp');
  await page.goto('/sales/insights');
  await expect(page.getByText('仅企业管理员可查看团队共性问题')).toBeVisible({ timeout: 10000 });
  await context.close();
});
