import { test, expect } from '@playwright/test';
import { API_URL, loginViaApi, newLoggedInPage } from './helpers/auth';

const COACH_SKILL_ID =
  process.env.E2E_COACH_SKILL_ID || '40000000-0000-0000-0000-000000000001';

test.describe('销售复盘全链路（真实 LLM）', () => {
  test.skip(!process.env.E2E_SALES_CHAIN, '设置 E2E_SALES_CHAIN=1 才运行');

  test('建复盘 → 报告 → 转线索 → 建任务 → 教练补充', async ({ browser, request }) => {
    test.setTimeout(300_000);
    const marker = `E2E-链路-${Date.now()}`;
    const lina = await loginViaApi(request, 'lina', '123456');
    const zhangwei = await loginViaApi(request, 'zhangwei', '123456');

    const created = await request.post(`${API_URL}/sales/reviews`, {
      headers: { Authorization: `Bearer ${lina.token}` },
      data: {
        coachSkillId: COACH_SKILL_ID,
        customerName: marker,
        topic: '全链路验收',
        shareTranscriptWithCoach: true,
        transcript:
          '销售：王总您好，想了解下您今年的培训预算。\n'
          + '客户：预算四十万，主要想解决新人上手慢。\n'
          + '销售：明白，我下周给您做方案演示。\n'
          + '客户：可以，下周二下午三点。',
      },
    });
    expect(created.ok()).toBeTruthy();
    const createdBody = await created.json();
    expect(createdBody.code).toBe(200);
    const sessionId: string = createdBody.data.session.id;

    const linaPage = await newLoggedInPage(browser, lina);
    const zhangweiPage = await newLoggedInPage(browser, zhangwei);
    try {
      // 1. 报告
      await linaPage.page.goto(`/sales/reviews/${sessionId}`);
      await expect(linaPage.page.getByRole('heading', { name: marker })).toBeVisible();
      await linaPage.page.getByRole('button', { name: '生成报告' }).click();

      const okText = linaPage.page.getByText('一句话结论');
      const failText = linaPage.page.getByText(/生成失败：/);
      await expect(okText.or(failText).first()).toBeVisible({ timeout: 180_000 });
      if (await failText.count()) {
        // 上游偶发返回非法 JSON，走产品的「重新生成」再试一次
        linaPage.page.once('dialog', d => d.accept());
        await linaPage.page.getByRole('button', { name: '重新生成报告' }).click();
        await expect(okText.or(failText).first()).toBeVisible({ timeout: 180_000 });
        if (await failText.count()) {
          throw new Error('报告重试后仍失败：' + (await failText.first().innerText()));
        }
      }

      // 2. 转线索
      await linaPage.page.getByRole('button', { name: '转线索' }).click();
      const leadModal = linaPage.page.locator('div.fixed').filter({ hasText: '转为线索' });
      const nameInput = leadModal.locator('input').first();
      if (!(await nameInput.inputValue())) {
        await nameInput.fill(marker);
      }
      await leadModal.getByRole('button', { name: '确认创建' }).click();
      if (await leadModal.getByRole('button', { name: '仍要新建' }).count()) {
        await leadModal.getByRole('button', { name: '仍要新建' }).click();
      }
      await expect(leadModal).toBeHidden({ timeout: 30_000 });

      // 3. 生成任务
      await linaPage.page.getByRole('button', { name: '生成任务' }).click();
      const taskModal = linaPage.page.locator('div.fixed').filter({ hasText: '生成跟进任务' });
      await expect(taskModal).toBeVisible();
      await taskModal.getByRole('button', { name: /创建 \d+ 条/ }).click();
      await expect(taskModal).toBeHidden({ timeout: 30_000 });
      await linaPage.page.goto('/sales/tasks');
      await expect(linaPage.page.getByRole('heading', { name: '跟进任务' })).toBeVisible();
      await expect(linaPage.page.getByText(/负责人：/).first()).toBeVisible({
        timeout: 30_000,
      });

      // 4. 请教练补充
      await linaPage.page.goto(`/sales/reviews/${sessionId}`);
      await linaPage.page.getByRole('button', { name: '请教练补充' }).click();
      await linaPage.page
        .getByPlaceholder('想请教练重点看什么？（可不填）')
        .fill('E2E：预算判断帮我看一下');
      await linaPage.page.getByRole('button', { name: '发送请求' }).click();
      await expect(linaPage.page.getByText('已请求教练补充，等待回复。')).toBeVisible({
        timeout: 30_000,
      });

      // 5. 教练补充
      await zhangweiPage.page.goto('/sales/coach/ledger');
      await zhangweiPage.page.getByRole('button', { name: '待我补充' }).click();
      await zhangweiPage.page.getByText(marker).first().click();
      await expect(zhangweiPage.page.getByRole('heading', { name: `${marker} · 教练补充` }))
        .toBeVisible({ timeout: 30_000 });
      await zhangweiPage.page
        .getByPlaceholder(/补充你的判断/)
        .fill('E2E 教练补充：预算不是问题，客户卡在决策链。');
      await zhangweiPage.page.getByRole('button', { name: '提交补充' }).click();
      await expect(zhangweiPage.page.getByText(/E2E 教练补充：预算不是问题/)).toBeVisible({
        timeout: 30_000,
      });

      // 6. 发起人看到补充
      await linaPage.page.reload();
      await expect(linaPage.page.getByText(/E2E 教练补充：预算不是问题/)).toBeVisible({
        timeout: 30_000,
      });
    } finally {
      await linaPage.context.close();
      await zhangweiPage.context.close();
    }
  });
});
