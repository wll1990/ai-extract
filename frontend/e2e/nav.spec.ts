import { test, expect, type Page } from '@playwright/test';

/**
 * 导航回归 — 顶栏业务域 + 域内二级菜单。
 *
 * 依赖演示企业账号（DEMO2026）：
 * - demo_admin 企业管理员：5 个域
 * - demo_learner 员工：2 个域，素材入口在「日常」
 */
test.use({ storageState: { cookies: [], origins: [] } });

async function login(page: Page, account: string) {
  await page.goto('/login', { waitUntil: 'networkidle' });
  await page.locator('input[autocomplete="organization"]').fill('DEMO2026');
  await page.locator('input[autocomplete="username"]').fill(account);
  await page.locator('input[autocomplete="current-password"]').fill('123456');
  await page.locator('button[type="submit"]').click();
  await page.waitForFunction(() => !!localStorage.getItem('token'), null, { timeout: 20_000 });
}

async function domainTabs(page: Page) {
  return page.locator('nav[aria-label="业务域"] button').allTextContents();
}

test('企业管理员：5 个域，切域进入该域第一项', async ({ page }) => {
  await login(page, 'demo_admin');
  await page.goto('/admin', { waitUntil: 'networkidle' });
  await expect(page.locator('nav[aria-label="业务域"]')).toBeVisible();

  expect(await domainTabs(page)).toEqual(['日常', '销售', '分身生产', '知识', '管理']);

  await page.getByRole('button', { name: '分身生产' }).click();
  await expect(page).toHaveURL(/\/admin\/skills$/);
  const items = await page.locator('aside nav button').allTextContents();
  expect(items[0]).toContain('分身管理');
  expect(items).not.toContain('运营总览');
});

test('深链按路径反推域：/admin/materials → 分身生产', async ({ page }) => {
  await login(page, 'demo_admin');
  await page.goto('/admin/materials', { waitUntil: 'networkidle' });

  const active = page.locator('nav[aria-label="业务域"] button[aria-current="page"]');
  await expect(active).toHaveText('分身生产');
  await expect(page.locator('aside nav button[aria-current="page"]')).toContainText('素材管理');
});

test('员工：只有日常与销售，素材入口在「日常」', async ({ page }) => {
  await login(page, 'demo_learner');
  await page.goto('/admin/materials', { waitUntil: 'networkidle' });

  expect(await domainTabs(page)).toEqual(['日常', '销售']);
  const items = await page.locator('aside nav button').allTextContents();
  expect(items.some(t => t.includes('上传素材'))).toBe(true);
  expect(items.some(t => t.includes('素材管理'))).toBe(false);
});

test('帮助与学习：产品导览、手册、FAQ、快捷入口', async ({ page }) => {
  await login(page, 'demo_admin');
  await page.goto('/admin', { waitUntil: 'networkidle' });

  await page.getByRole('button', { name: '帮助与学习' }).click();
  const menu = page.getByRole('menu');
  await expect(menu.getByRole('menuitem')).toHaveCount(4);

  await menu.getByRole('menuitem', { name: /产品导览/ }).click();
  await expect(page.getByText('产品导览 ·', { exact: false })).toBeVisible();
  await expect(page.getByRole('button', { name: '从头开始', exact: true })).toBeVisible();
});

test('⌘K 快捷入口可搜索并跳转', async ({ page }) => {
  await login(page, 'demo_admin');
  await page.goto('/admin', { waitUntil: 'networkidle' });

  await page.keyboard.press('Control+k');
  const dialog = page.getByRole('dialog', { name: '快捷入口' });
  await expect(dialog).toBeVisible();
  await dialog.locator('input[aria-label="搜索页面"]').fill('知识库');
  await dialog.getByRole('button', { name: /知识库/ }).click();
  await expect(page).toHaveURL(/\/admin\/knowledge$/);
});

test('FAQ 深链打开常见问题页签', async ({ page }) => {
  await login(page, 'demo_admin');
  await page.goto('/help#faq', { waitUntil: 'networkidle' });
  await expect(page.getByRole('button', { name: '常见问题', exact: true })).toHaveClass(/bg-primary-light/);
});
