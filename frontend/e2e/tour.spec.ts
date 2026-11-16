import { test, expect, type Page } from '@playwright/test';
import { CORE_CHAPTERS } from '../src/lib/tour/core';

/**
 * 产品导览回归：
 * - 逐章启动，断言当前章每一步都能找到锚点（无「没找到」）
 * - 覆盖售前（demo_admin）与员工（demo_learner）两条轨道
 *
 * 依赖演示企业数据；未跑 scripts/tour-seed.sql 时，「知识怎么更新」章可能少一步。
 */
test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(180_000);

async function login(page: Page, account: string) {
  // 等 React hydration 完成再填，否则受控输入会被初始 state 覆盖
  await page.goto('/login', { waitUntil: 'networkidle' });
  await page.locator('input[autocomplete="organization"]').fill('DEMO2026');
  await page.locator('input[autocomplete="username"]').fill(account);
  await page.locator('input[autocomplete="current-password"]').fill('123456');
  await page.locator('button[type="submit"]').click();
  await page.waitForFunction(() => !!localStorage.getItem('token'), null, { timeout: 20_000 });
}

async function openTour(page: Page) {
  await page.getByRole('button', { name: '帮助与学习' }).click();
  await page.getByRole('menuitem', { name: /产品导览/ }).click();
  await page.getByRole('button', { name: '从头开始', exact: true }).click();
  await expect(page.locator('[data-tour-card]')).toBeVisible({ timeout: 20_000 });
}

test('售前轨道：完整走通，每步都能定位', async ({ page }) => {
  await login(page, 'demo_admin');
  await page.goto('/admin', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  await openTour(page);

  const salesChapters = CORE_CHAPTERS.filter(c => c.tracks.includes('sales'));
  const maxSteps = salesChapters.reduce((n, c) => n + c.steps.length, 0);
  let walked = 0;

  for (let i = 0; i < maxSteps + 5; i++) {
    await page.waitForTimeout(700);
    const card = page.locator('[data-tour-card]');
    await expect(card).toBeVisible();
    const text = await card.innerText();
    expect(text).not.toContain('没找到');
    walked += 1;
    const nextBtn = page.locator('[data-tour-card] button', { hasText: '下一步 →' });
    if (await nextBtn.count() === 0) {
      await page.locator('[data-tour-card] button', { hasText: '完成' }).click();
      break;
    }
    await nextBtn.click();
  }

  // 演示数据齐全时应覆盖至少 20 步；少于此说明有章节被数据过滤
  expect(walked).toBeGreaterThanOrEqual(20);
});

test('员工轨道：完整走通且不出现 403', async ({ page }) => {
  await login(page, 'demo_learner');
  await page.goto('/skills', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1200);
  await openTour(page);

  const errors: string[] = [];
  const badResponses: string[] = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('response', r => {
    if (r.status() >= 400 && !r.url().includes('favicon')) {
      badResponses.push(`${r.status()} ${r.url()}`);
    }
  });

  for (let i = 0; i < 40; i++) {
    await page.waitForTimeout(700);
    const card = page.locator('[data-tour-card]');
    await expect(card).toBeVisible();
    const text = await card.innerText();
    expect(text).not.toContain('没找到');
    const nextBtn = page.locator('[data-tour-card] button', { hasText: '下一步 →' });
    if (await nextBtn.count() === 0) {
      await page.locator('[data-tour-card] button', { hasText: '完成' }).click();
      break;
    }
    await nextBtn.click();
  }
  expect(errors).toEqual([]);
  expect(badResponses).toEqual([]);
});
