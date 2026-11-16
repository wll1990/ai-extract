/**
 * 门户视觉与交互自检。
 *
 * 用法：node scripts/portal-check.mjs [baseUrl]
 * 产出：artifacts/portal-*.png + 控制台错误列表（有错即非 0 退出）
 */
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';

const BASE = process.argv[2] || 'http://localhost:3000';
const OUT = new URL('../artifacts/', import.meta.url).pathname;
mkdirSync(OUT, { recursive: true });

const SCENES = [
  ['chat', '问销冠'],
  ['practice', '实战对练'],
  ['review', '复盘'],
  ['coach', '补充与认证'],
  ['convert', '转下一步'],
];

const errors = [];
const browser = await chromium.launch({ channel: process.env.E2E_CHANNEL || 'chrome' });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on('pageerror', e => errors.push(`pageerror: ${e.message}`));
page.on('console', m => {
  if (m.type() === 'error') {
    const loc = m.location();
    errors.push(`console: ${m.text()} @ ${loc.url || '(unknown)'}`);
  }
});
page.on('requestfailed', r => errors.push(`requestfailed: ${r.url()} ${r.failure()?.errorText ?? ''}`));
page.on('response', r => {
  if (r.status() >= 400) errors.push(`http ${r.status()}: ${r.url()}`);
});

await page.goto(`${BASE}/portal`, { waitUntil: 'networkidle' });
await page.waitForTimeout(3000);
await page.screenshot({ path: `${OUT}portal-full.png`, fullPage: true });
console.log('full page  OK');

// 价值证明区
const proof = page.locator('#proof');
await proof.scrollIntoViewIfNeeded();
await page.waitForTimeout(500);
const proofCards = await proof.locator('.proof-grid article').count();
console.log(`proof cards = ${proofCards}（期望 3）`);
await page.screenshot({ path: `${OUT}portal-proof.png` });

// 能力卡
const valueCards = await page.locator('#capabilities .value-card').count();
const setupCards = await page.locator('#setup .setup-grid article').count();
const pathSteps = await page.locator('#rollout .path-step').count();
console.log(`value cards = ${valueCards}（期望 3） · setup = ${setupCards}（期望 3） · rollout = ${pathSteps}（期望 3）`);

// 演示区：逐幕截图
await page.locator('#demo').scrollIntoViewIfNeeded();
await page.waitForTimeout(1200);

for (const [key, label] of SCENES) {
  await page.getByRole('button', { name: label, exact: true }).click();
  await page.waitForTimeout(key === 'chat' ? 4500 : 6500);
  const shot = `${OUT}portal-scene-${key}.png`;
  await page.locator('#demo .demo-stage').screenshot({ path: shot });
  const blocks = await page.locator('#demo .demo-stage').innerText();
  console.log(`scene ${key}: ${blocks.replace(/\s+/g, ' ').slice(0, 60)}…`);
}

// 行业场景切换
await page.getByRole('button', { name: '问销冠', exact: true }).click();
await page.waitForTimeout(300);
await page.getByRole('button', { name: '制造业设备', exact: true }).click();
await page.waitForTimeout(4200);
const manufacturingQuestion = await page.locator('#demo .user-message p').first().innerText();
console.log(`scenario switch: ${manufacturingQuestion.slice(0, 30)}…`);
await page.locator('#demo .demo-stage').screenshot({ path: `${OUT}portal-scene-manufacturing.png` });

// 移动端
const mobile = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
mobile.on('pageerror', e => errors.push(`mobile pageerror: ${e.message}`));
await mobile.goto(`${BASE}/portal`, { waitUntil: 'networkidle' });
await mobile.waitForTimeout(800);
const overflow = await mobile.evaluate(
  () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
);
console.log(`mobile horizontal overflow = ${overflow}px（期望 0）`);
await mobile.screenshot({ path: `${OUT}portal-mobile.png`, fullPage: true });

await browser.close();

if (errors.length) {
  console.error('\nERRORS:');
  errors.forEach(e => console.error(' -', e));
  process.exit(1);
}
console.log('\nno console errors');
