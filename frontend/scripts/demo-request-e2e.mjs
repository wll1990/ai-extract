/**
 * 预约演示：前三端到端验证。
 *
 * 走真实链路：门户表单提交 → 后端落库 → 运营后台列表 → 更新跟进状态。
 * 用法：node scripts/demo-request-e2e.mjs [baseUrl]
 */
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';

const BASE = process.argv[2] || 'http://localhost:3000';
const OUT = new URL('../artifacts/', import.meta.url).pathname;
mkdirSync(OUT, { recursive: true });

const stamp = Date.now();
const CONTACT = `139${String(stamp).slice(-8)}`;
const COMPANY = `端到端验证 ${stamp}`;

const errors = [];
const browser = await chromium.launch({ channel: process.env.E2E_CHANNEL || 'chrome' });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
page.on('pageerror', e => errors.push(`pageerror: ${e.message}`));

// ---- 1. 门户提交 ----
await page.goto(`${BASE}/portal`, { waitUntil: 'networkidle' });
await page.locator('#contact').scrollIntoViewIfNeeded();
await page.locator('.demo-request-form input').nth(0).fill(COMPANY);
await page.locator('.demo-request-form input').nth(1).fill('王测试');
await page.locator('.demo-request-form input').nth(2).fill('销售负责人');
await page.locator('.demo-request-form input').nth(3).fill('40 人');
await page.locator('.demo-request-form input').nth(4).fill(CONTACT);
await page.locator('.demo-request-form textarea').fill('客户拿竞品报价压价，团队不知道该怎么回。');
await page.locator('.demo-request-form button[type=submit]').click();
await page.locator('.demo-request-success').waitFor({ timeout: 10000 });
console.log('门户提交成功');
await page.locator('#contact').screenshot({ path: `${OUT}demo-request-success.png` });

// ---- 2. 登录运营后台 ----
const loginRes = await page.request.post(`${BASE}/api/v1/auth/login`, {
  data: {
    account: 'admin',
    password: '123456',
    companyId: 'c0000000-0000-0000-0000-000000000001',
  },
});
const loginBody = await loginRes.json();
const token = loginBody?.data?.token;
if (!token) throw new Error('登录失败：拿不到 token');
await page.context().addCookies([{ name: 'token', value: token, url: BASE }]);
// 中间件只认 cookie，apiClient 只认 localStorage —— 两个都要注入
await page.addInitScript(t => window.localStorage.setItem('token', t), token);
await page.goto(`${BASE}/admin/demo-requests`, { waitUntil: 'networkidle' });
await page.waitForTimeout(600);

// ---- 3. 列表里能看到这条 ----
const row = page.locator('tr', { hasText: COMPANY }).first();
await row.waitFor({ timeout: 10000 });
const statusText = await row.locator('td').nth(4).innerText();
console.log(`后台可见：${COMPANY} / 状态 ${statusText.trim()}`);
await page.screenshot({ path: `${OUT}admin-demo-requests.png`, fullPage: true });

// ---- 4. 更新状态 ----
await row.getByRole('button', { name: '标记已联系' }).click();
await page.waitForTimeout(900);
const after = await page.locator('tr', { hasText: COMPANY }).first().locator('td').nth(4).innerText();
console.log(`状态更新后：${after.trim()}`);
await page.screenshot({ path: `${OUT}admin-demo-requests-contacted.png`, fullPage: true });

// ---- 5. 刷新后仍在（证明落库、不是前端乐观更新） ----
await page.reload({ waitUntil: 'networkidle' });
await page.waitForTimeout(600);
const persisted = await page.locator('tr', { hasText: COMPANY }).first().locator('td').nth(4).innerText();
console.log(`刷新后：${persisted.trim()}`);

await browser.close();

const expected = ['已联系', '已联系'];
if (!after.includes(expected[0]) || !persisted.includes(expected[1])) {
  console.error('FAILED: 状态没有正确更新或没有落库');
  process.exit(1);
}
if (errors.length) {
  console.error('页面错误:', errors);
  process.exit(1);
}
console.log('\n端到端 OK');
