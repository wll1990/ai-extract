#!/usr/bin/env node
/**
 * 真实截图：登录演示账号，按清单逐页截图。
 * 列表页先断言有数据，空页面直接失败，避免拿空列表充数。
 *
 * 用法：node scripts/capture-help-shots.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { chromium } from '../../node_modules/playwright/index.mjs';

const BASE = process.env.HELP_BASE_URL || 'http://localhost:3000';
const OUT = path.resolve('public/help/shots');
const COMPANY_CODE = process.env.DEMO_COMPANY_CODE || 'DEMO2026';
const ACCOUNT = process.env.DEMO_ACCOUNT || 'demo_admin';
const PASSWORD = process.env.DEMO_PASSWORD || '123456';

fs.mkdirSync(OUT, { recursive: true });

const commit = (() => {
  try { return execSync('git rev-parse --short HEAD', { encoding: 'utf8' }).trim(); }
  catch { return 'unknown'; }
})();

const SHOTS = [
  { file: '02-upload.png', url: '/admin/skills/upload', wait: '[data-tour="upload-guide"]' },
  { file: '03-interview.png', url: '/interview/create', wait: '[data-tour="interview-create-cta"]' },
  { file: '04-materials.png', url: '/admin/skills/d3000000-0000-0000-0000-000000000001/materials', wait: '[data-tour="materials-table"]', requireRows: '[data-tour="materials-table"] tbody tr' },
  { file: '05-audit.png', url: '/admin/skills/d3000000-0000-0000-0000-000000000001/audit', wait: '[data-tour="audit-step-tabs"]' },
  { file: '06-chat.png', url: '/skill/d3000000-0000-0000-0000-000000000001', wait: '[data-tour="skill-mode-tabs"]' },
  { file: '07-review.png', url: '/sales/reviews', wait: '[data-tour="review-new-cta"]' },
  { file: '08-gap.png', url: '/admin/insights/d3000000-0000-0000-0000-000000000001', wait: '[data-tour="gap-panel"]' },
  { file: '09-knowledge.png', url: '/admin/knowledge', wait: '[data-tour="knowledge-tabs"]' },
  { file: '10-users.png', url: '/admin/users', wait: '[data-tour="user-create"]' },
  { file: '11-training.png', url: '/admin/training/plans', wait: '[data-tour="training-tabs"]' },
  { file: '12-share.png', url: '/admin/skills', wait: '[data-tour="admin-skills-share"]' },
];

const browser = await chromium.launch({ channel: process.env.E2E_CHANNEL || 'chrome', headless: true });

// 登录页截图（未登录）
const anon = await browser.newPage({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
await anon.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
await anon.waitForTimeout(600);
await anon.screenshot({ path: path.join(OUT, '01-login.png') });
await anon.close();

const page = await browser.newPage({
  viewport: { width: 1440, height: 900 },
  deviceScaleFactor: 1,
  locale: 'zh-CN',
  reducedMotion: 'reduce',
});
await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
await page.locator('input[autocomplete="organization"]').fill(COMPANY_CODE);
await page.locator('input[autocomplete="username"]').fill(ACCOUNT);
await page.locator('input[autocomplete="current-password"]').fill(PASSWORD);
await page.locator('button[type="submit"]').click();
await page.waitForFunction(() => !!localStorage.getItem('token'), null, { timeout: 20000 });

const manifest = [{ file: '01-login.png', url: '/login', at: new Date().toISOString() }];

for (const shot of SHOTS) {
  await page.goto(`${BASE}${shot.url}`, { waitUntil: 'networkidle' });
  await page.waitForSelector(shot.wait, { timeout: 20000 });
  if (shot.requireRows) {
    const count = await page.locator(shot.requireRows).count();
    if (count === 0) throw new Error(`${shot.file}: 列表为空，拒绝截图`);
  }
  await page.waitForTimeout(900);
  await page.screenshot({ path: path.join(OUT, shot.file) });
  manifest.push({ file: shot.file, url: shot.url, at: new Date().toISOString() });
  console.log('✓', shot.file);
}

// 管理员仍能进入 /help（登录态），截一张
await page.goto(`${BASE}/help`, { waitUntil: 'networkidle' });
await page.waitForTimeout(800);
await page.screenshot({ path: path.join(OUT, '13-help.png') });
manifest.push({ file: '13-help.png', url: '/help', at: new Date().toISOString() });

fs.writeFileSync(path.join(OUT, 'manifest.json'), JSON.stringify({ commit, capturedAt: new Date().toISOString(), shots: manifest }, null, 2));
console.log(`完成 ${manifest.length} 张，manifest 已写入 ${OUT}`);
await browser.close();
