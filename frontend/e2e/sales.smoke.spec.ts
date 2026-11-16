import { test, expect } from '@playwright/test';

/**
 * 销售复盘冒烟 —— 登录态 + 页面可渲染 + 关键入口存在。
 * 不触发 LLM，不依赖开发库里有没有复盘数据。
 */
test.describe('销售复盘冒烟', () => {
  test('复盘列表与新建入口', async ({ page }) => {
    await page.goto('/sales/reviews');
    await expect(page.getByRole('heading', { name: '销售复盘' })).toBeVisible({
      timeout: 45_000,
    });
    await expect(page.getByRole('link', { name: '新建复盘' })).toBeVisible();

    const rows = page.getByRole('link', { name: /未命名客户|华星科技/ });
    const empty = page.getByText('还没有复盘，选一位销冠');
    await expect(rows.first().or(empty)).toBeVisible({ timeout: 30_000 });
  });

  test('新建复盘页有教练选择器', async ({ page }) => {
    await page.goto('/sales/reviews/new');
    await expect(page.getByRole('heading', { name: '新建复盘' })).toBeVisible({
      timeout: 45_000,
    });
    await expect(page.getByRole('button', { name: /选择一位教练/ })).toBeVisible();
    await expect(page.getByText('对话文本', { exact: true })).toBeVisible();
  });

  test('线索与任务页可打开', async ({ page }) => {
    await page.goto('/sales/leads');
    await expect(page.getByRole('heading', { name: '线索' })).toBeVisible({
      timeout: 45_000,
    });

    await page.goto('/sales/tasks');
    await expect(page.getByRole('heading', { name: '跟进任务' })).toBeVisible({
      timeout: 30_000,
    });
  });

  test('企业 AI 模型页可打开', async ({ page }) => {
    await page.goto('/admin/settings/ai-model');
    await expect(page.getByRole('heading', { name: '企业 AI 模型' })).toBeVisible({
      timeout: 45_000,
    });
    await expect(page.getByRole('button', { name: '保存' })).toBeVisible();
  });
});
