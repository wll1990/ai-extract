import { test, expect, request, type APIRequestContext, type Browser } from '@playwright/test';
import { loginViaApi, newLoggedInPage, API_URL } from './helpers/auth';

/**
 * 真实页面交互 + 前端/后端契约断言。
 *
 * 与 qa.ui.spec.ts 的区别：这里必须点真实按钮、走真实路由、由前端发请求，
 * 并断言「页面显示的内容 / 状态」与同一时刻接口返回一致，用来抓字段漂移和渲染断链。
 *
 * 数据由测试自己通过 API 造（runId 命名），跑完由 scripts/qa/seed/qa-cleanup.sql 按 runId 清理。
 */

const RUN_ID = process.env.E2E_RUN_ID || 'local';
const PASSWORD = process.env.E2E_PASSWORD || '';
const ADMIN = process.env.E2E_ACCOUNT || 'qa_admin';
const EMP = process.env.E2E_EMP || 'qa_emp';
const EMP_ID = process.env.E2E_EMP_ID || '0a000000-0000-0000-0000-000000000012';
const QA_KNOWLEDGE_ACTIVE = '0a000000-0000-0000-0000-000000000042';

async function adminApi() {
  const ctx = await request.newContext();
  const session = await loginViaApi(ctx, ADMIN, PASSWORD);
  return { ctx, token: session.token };
}

async function call(
  ctx: APIRequestContext,
  token: string,
  method: 'GET' | 'POST' | 'PUT',
  path: string,
  data?: unknown,
) {
  return ctx.fetch(`${API_URL}${path}`, {
    method,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data,
  });
}

async function pageAs(browser: Browser, account: string) {
  const ctx = await request.newContext();
  const session = await loginViaApi(ctx, account, PASSWORD);
  await ctx.dispose();
  return newLoggedInPage(browser, session);
}

test('知识库：搜索→审批走真实页面，页面状态与接口一致', async ({ browser }) => {
  const admin = await adminApi();
  const title = `QA-${RUN_ID}-UI知识`;
  const create = await call(admin.ctx, admin.token, 'POST', '/admin/knowledge', {
    title,
    category: 'methodology',
    sourceType: 'tenant',
    content: `${title}：客户预算未批时，先确认决策链，再准备对比材料。`,
    status: 'pending',
    expireAt: '2027-12-31T23:59:59',
  });
  expect(create.status()).toBe(200);
  const created = (await create.json()).data;

  const { page, context } = await pageAs(browser, ADMIN);
  try {
    await page.goto('/admin/knowledge');
    await page.getByPlaceholder(/搜索标题/).fill(title);
    await page.getByRole('button', { name: '搜索' }).click();

    // 契约 1：接口返回 1 条，页面也只能渲染这 1 条
    const listRes = await call(
      admin.ctx, admin.token, 'GET',
      `/admin/knowledge?keyword=${encodeURIComponent(title)}&page=1&size=20`,
    );
    expect(listRes.status()).toBe(200);
    expect((await listRes.json()).data.total).toBe(1);

    const row = page.locator('div.grid-cols-12').filter({ hasText: title });
    await expect(row).toHaveCount(1);
    await expect(row).toContainText(title);

    // 真实交互：点「审批」（数据自带 expireAt，不应弹 prompt）
    await row.getByRole('button', { name: '审批' }).click();
    await expect(row).toContainText('已生效');

    // 契约 2：页面显示的状态必须和接口落库状态一致
    const detailRes = await call(admin.ctx, admin.token, 'GET', `/admin/knowledge/${created.id}`);
    const detail = (await detailRes.json()).data;
    expect(detail.status).toBe('active');
    expect(detail.title).toBe(title);
  } finally {
    await context.close();
    await admin.ctx.dispose();
  }
});

test('素材上传：粘贴文本→上传成功→进入审核页，素材可在接口查到', async ({ browser }) => {
  const admin = await adminApi();
  const title = `QA-${RUN_ID}-UI素材`;
  const { page, context } = await pageAs(browser, ADMIN);
  try {
    await page.goto('/admin/skills/upload');
    await page.getByRole('button', { name: /粘贴文本/ }).click();

    const selects = page.locator('select');
    await selects.nth(0).selectOption({ index: 1 });
    await selects.nth(1).selectOption({ index: 1 });
    await page.getByPlaceholder('给这段素材起个名字，方便后续查找').fill(title);
    // 文本带上本次 runId：同空间内容相似度 100% 会被判重复，避免历史残留导致用例假失败
    await page.locator('textarea').fill(
      `${title}。客户说预算还没有批下来，价格又比竞品高。我先请对接人帮我梳理内部评审流程，确认谁签字、谁使用、谁反对，再按清单准备对比材料。`
      + '对比时不只比单价，而是把实施周期、交付团队、售后响应和培训支持拆开算总拥有成本。如果客户仍然只看价格，我会提出分阶段验收和分期付款，'
      + '把首付款比例降下来，同时约定达到效果后的尾款节点。竞品对比时我从不贬低对手，只讲我们多出来的交付能力和已经跑通的同行业案例，让客户自己判断溢价是否值得。',
    );
    await page.getByRole('button', { name: '上传文本素材' }).click();

    await expect(page.getByText('上传成功')).toBeVisible({ timeout: 60_000 });
    await page.getByRole('button', { name: '进入审核' }).click();
    await expect(page).toHaveURL(/\/admin\/skills\/[^/]+\/audit/);

    // 契约：页面跳转到的 skillId 下，接口必须能查到刚上传的素材
    const skillId = page.url().match(/skills\/([^/]+)\/audit/)![1];
    const res = await call(admin.ctx, admin.token, 'GET', `/admin/skills/${skillId}/materials?page=1&size=50`);
    expect(res.status()).toBe(200);
    const data = (await res.json()).data;
    const found = (data.content || []).some((m: { fileName?: string }) => String(m.fileName || '').startsWith(title));
    expect(found, '接口应能查到刚上传的素材').toBeTruthy();

    // 审核页真实渲染（萃取报告区能打开，不是白屏）
    await expect(page.getByText(/审核|萃取|颗粒/).first()).toBeVisible({ timeout: 30_000 });
  } finally {
    await context.close();
    await admin.ctx.dispose();
  }
});

test('培训学习：展开→开始→标记完成走真实页面，进度与接口一致', async ({ browser }) => {
  const admin = await adminApi();
  const planName = `QA-${RUN_ID}-UI培训`;
  const taskTitle = `QA-${RUN_ID}-UI阅读`;

  const planRes = await call(admin.ctx, admin.token, 'POST', '/admin/training/plans', {
    name: planName,
    description: '页面交互测试：展开任务并标记已读',
  });
  expect(planRes.status()).toBe(200);
  const planId = (await planRes.json()).data.id;

  const taskRes = await call(admin.ctx, admin.token, 'POST', `/admin/training/plans/${planId}/tasks`, {
    type: 'reading',
    phaseNo: 1,
    phaseName: '认知',
    title: taskTitle,
    resourceType: 'knowledge',
    resourceId: QA_KNOWLEDGE_ACTIVE,
    isRequired: true,
    orderNo: 1,
  });
  expect(taskRes.status()).toBe(200);

  expect((await call(admin.ctx, admin.token, 'POST', `/admin/training/plans/${planId}/publish`)).status()).toBe(200);

  const cohortRes = await call(admin.ctx, admin.token, 'POST', '/admin/training/cohorts', {
    planId,
    planVersion: 1,
    name: `QA-${RUN_ID}-UI批次`,
  });
  expect(cohortRes.status()).toBe(200);
  const cohortId = (await cohortRes.json()).data.id;

  const assignRes = await call(admin.ctx, admin.token, 'POST', '/admin/training/assignments/batch', {
    cohortId,
    learnerIds: [EMP_ID],
  });
  expect(assignRes.status()).toBe(200);
  expect((await assignRes.json()).data.assigned).toBe(1);

  const empCtx = await request.newContext();
  const empSession = await loginViaApi(empCtx, EMP, PASSWORD);

  const { page, context } = await newLoggedInPage(browser, empSession);
  try {
    await page.goto('/training');
    await expect(page.getByRole('heading', { name: '我的培训' })).toBeVisible();

    // 契约：页面上的计划名来自接口分配列表
    const assignmentsRes = await empCtx.fetch(`${API_URL}/me/training/assignments?page=1&size=50`, {
      headers: { Authorization: `Bearer ${empSession.token}` },
    });
    const assignments = (await assignmentsRes.json()).data.content as Array<{ id: string; planName: string }>;
    const mine = assignments.find(a => a.planName === planName);
    expect(mine, '接口应能查到本次分配的培训').toBeTruthy();

    const card = page.getByText(planName).first().locator('xpath=ancestor::div[contains(@class,"rounded-xl")][1]');
    await card.getByRole('button', { name: '展开任务' }).click();

    const taskRow = card
      .getByText(taskTitle)
      .first()
      .locator('xpath=ancestor::div[contains(@class,"rounded-lg")][1]');
    await expect(taskRow).toBeVisible();
    await taskRow.getByRole('button', { name: '开始' }).click();

    await expect(page).toHaveURL(/\/training\/assignments\/.+\/tasks\//);
    const ackBtn = page.getByRole('button', { name: '已阅读，标记完成' });
    await expect(ackBtn).toBeVisible();
    page.once('dialog', dialog => dialog.accept());
    await ackBtn.click();

    // 标记成功后页面会回到「我的培训」，任务状态由接口刷新为已完成
    await expect(page).toHaveURL(/\/training$/);
    const cardAfter = page.getByText(planName).first().locator('xpath=ancestor::div[contains(@class,"rounded-xl")][1]');
    await cardAfter.getByRole('button', { name: '展开任务' }).click();
    const taskRowAfter = cardAfter
      .getByText(taskTitle)
      .first()
      .locator('xpath=ancestor::div[contains(@class,"rounded-lg")][1]');
    // 阅读任务通过后按钮改为「查看内容」：内容仍可回看，不能用「已完成」把入口藏起来
    await expect(taskRowAfter).toContainText('查看内容');
    await expect(taskRowAfter).toContainText('已通过');

    // 契约：页面显示完成后，接口进度同步（completedTasks ≥ 1）
    const afterRes = await empCtx.fetch(`${API_URL}/me/training/assignments?page=1&size=50`, {
      headers: { Authorization: `Bearer ${empSession.token}` },
    });
    const after = (await afterRes.json()).data.content as Array<{
      id: string; planName: string; completedTasks: number; status: string;
    }>;
    const updated = after.find(a => a.id === mine!.id);
    expect(updated, '分配记录应仍在列表中').toBeTruthy();
    expect(updated!.completedTasks).toBeGreaterThanOrEqual(1);
  } finally {
    await context.close();
    await empCtx.dispose();
    await admin.ctx.dispose();
  }
});
