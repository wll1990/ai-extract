#!/usr/bin/env node
/**
 * 从 /help 导出操作手册 PDF。
 * 用法：node scripts/export-help-pdf.mjs
 * 输出：../docs/manual/操作手册.pdf
 */
import path from 'node:path';
import { chromium } from '../../node_modules/playwright/index.mjs';

const BASE = process.env.HELP_BASE_URL || 'http://localhost:3000';
const OUT = path.resolve('../docs/manual/操作手册.pdf');

const browser = await chromium.launch({ channel: process.env.E2E_CHANNEL || 'chrome', headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });

await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
await page.locator('input[autocomplete="organization"]').fill(process.env.DEMO_COMPANY_CODE || 'DEMO2026');
await page.locator('input[autocomplete="username"]').fill(process.env.DEMO_ACCOUNT || 'demo_admin');
await page.locator('input[autocomplete="current-password"]').fill(process.env.DEMO_PASSWORD || '123456');
await page.locator('button[type="submit"]').click();
await page.waitForFunction(() => !!localStorage.getItem('token'), null, { timeout: 20_000 });

await page.goto(`${BASE}/help`, { waitUntil: 'networkidle' });
await page.waitForTimeout(1200);
await page.emulateMedia({ media: 'print' });
await page.pdf({
  path: OUT,
  format: 'A4',
  printBackground: true,
  margin: { top: '16mm', bottom: '16mm', left: '14mm', right: '14mm' },
});
console.log('PDF 已生成:', OUT);
await browser.close();
