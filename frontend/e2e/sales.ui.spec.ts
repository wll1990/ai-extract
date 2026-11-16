import { test, expect } from '@playwright/test';
import { API_URL, loginViaApi, newLoggedInPage } from './helpers/auth';

test.describe('销售页面交互', () => {
  test('报告版本切换与历史只读', async ({ browser, request }) => {
    const session = await loginViaApi(request, 'zhangwei', '123456');
    // 找一个真正有多版本的复盘，不依赖列表第一行
    const listRes = await request.get(`${API_URL}/sales/reviews?page=1&size=20`, {
      headers: { Authorization: `Bearer ${session.token}` },
    });
    const reviews = (await listRes.json()).data?.content ?? [];
    let reviewId: string | null = null;
    for (const r of reviews) {
      const rep = await request.get(`${API_URL}/sales/reviews/${r.id}/reports`, {
        headers: { Authorization: `Bearer ${session.token}` },
      });
      const reports = (await rep.json()).data ?? [];
      if (reports.length > 1) {
        reviewId = r.id;
        break;
      }
    }
    if (!reviewId) {
      test.skip(true, '开发库没有多版本复盘');
    }
    const { context, page } = await newLoggedInPage(browser, session);
    try {
      await page.goto(`/sales/reviews/${reviewId}`);
      await expect(page.getByText('对话段落')).toBeVisible({ timeout: 30_000 });
      const selector = page.locator('select').first();
      await expect(selector).toBeVisible();
      await selector.selectOption({ index: 1 });
      await expect(page.getByText('历史版本，只读')).toBeVisible();
      await expect(page.getByText(/版本对比 v/)).toBeVisible();
    } finally {
      await context.close();
    }
  });

  test('线索详情与联系人区', async ({ browser, request }) => {
    const session = await loginViaApi(request, 'zhangwei', '123456');
    const { context, page } = await newLoggedInPage(browser, session);
    try {
      await page.goto('/sales/leads');
      const first = page.locator('tbody a[href^="/sales/leads/"]').first();
      if ((await first.count()) === 0) {
        test.skip(true, '开发库没有线索数据');
      }
      await first.click();
      await expect(page.getByText('联系人', { exact: true })).toBeVisible({ timeout: 30_000 });
      await expect(page.getByText('活动时间线')).toBeVisible();
      await expect(page.getByRole('button', { name: '+ 添加联系人' })).toBeVisible();
    } finally {
      await context.close();
    }
  });

  test('任务编辑弹层', async ({ browser, request }) => {
    const session = await loginViaApi(request, 'zhangwei', '123456');
    const { context, page } = await newLoggedInPage(browser, session);
    try {
      await page.goto('/sales/tasks');
      await expect(page.getByRole('heading', { name: '跟进任务' })).toBeVisible();
      await page.waitForTimeout(1500);
      const edit = page.getByRole('button', { name: '编辑' }).first();
      if ((await edit.count()) === 0) {
        test.skip(true, '开发库没有待办任务');
      }
      await edit.click();
      await expect(page.getByText('编辑任务')).toBeVisible();
      await expect(page.getByRole('button', { name: '保存' })).toBeVisible();
    } finally {
      await context.close();
    }
  });
});
