import { test, expect } from '@playwright/test';

/**
 * 培训模块页面冒烟 —— 只验证「登录态 + 页面可渲染 + 关键入口可点」，
 * 依赖真实后端与开发库中的培训数据；数据为空时相关用例自动跳过。
 */
test.describe('培训冒烟', () => {
  test('管理端：计划列表与计划编辑器', async ({ page }) => {
    await page.goto('/admin/training/plans');
    // Next dev 首次访问该路由需要现场编译，放宽首屏等待
    await expect(page.getByRole('heading', { name: '培训计划' })).toBeVisible({ timeout: 45_000 });

    const edit = page.getByRole('button', { name: '编辑 / 批次' }).first();
    const empty = page.getByText('还没有培训计划');
    // 数据加载晚于标题渲染：等列表或空态出现
    await expect(edit.or(empty)).toBeVisible({ timeout: 30_000 });
    if (await empty.isVisible().catch(() => false)) {
      test.skip(true, '开发库没有培训计划');
    }
    await edit.click();
    await expect(page.getByText('阶段与任务')).toBeVisible();
    await expect(page.getByRole('button', { name: '学员视角预览' })).toBeVisible();
  });

  test('管理端：批次列表进入进度页', async ({ page }) => {
    await page.goto('/admin/training/plans');
    await page.getByRole('button', { name: /培训批次/ }).click();

    const view = page.getByRole('button', { name: '查看进度' }).first();
    const empty = page.getByText('还没有培训批次');
    await expect(view.or(empty)).toBeVisible({ timeout: 30_000 });
    if (await empty.isVisible().catch(() => false)) {
      test.skip(true, '开发库没有培训批次');
    }
    await view.click();

    await expect(page.getByText('学员', { exact: true }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: '刷新' })).toBeVisible();
  });

  test('通知铃铛可打开', async ({ page }) => {
    await page.goto('/training');
    await page.getByTitle('通知').click();
    await expect(page.getByText('全部已读')).toBeVisible();
  });

  test('学员端：我的培训可展开任务', async ({ page }) => {
    await page.goto('/training');
    await expect(page.getByRole('heading', { name: '我的培训' })).toBeVisible();

    const toggle = page.getByText('展开任务').first();
    if (await toggle.count() === 0) {
      test.skip(true, '当前账号没有培训分配');
    }
    await toggle.click();
    await expect(page.getByText('收起').first()).toBeVisible();
  });
});
