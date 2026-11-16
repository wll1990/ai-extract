#!/usr/bin/env node
/**
 * 门户保真度对比：同一视口分别截 MindSmith 展示稿与我们 /portal，做像素 diff。
 *
 * 用法（在 frontend/ 下执行，依赖都在 frontend/node_modules）：
 *   node ../scripts/fidelity-diff.mjs \
 *     --ours http://localhost:3000/portal \
 *     --ref  http://127.0.0.1:5173/portal \
 *     --out  ../artifacts/fidelity
 *
 * --ref 指向静态构建产物时，脚本会先加载站点根路径，再用 pushState 进入目标路由，
 * 避免依赖 SPA fallback。
 *
 * 退出码：diff <= threshold 为 0，否则为 1。
 */

import fs from 'node:fs';
import path from 'node:path';
import { chromium } from '@playwright/test';
import { PNG } from 'pngjs';
import pixelmatch from 'pixelmatch';

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const ours = arg('ours', 'http://localhost:3000/portal');
const ref = arg('ref', 'http://127.0.0.1:5173/portal');
const outDir = arg('out', 'artifacts/fidelity');
const threshold = Number(arg('threshold', '0.02'));
const width = Number(arg('width', '1440'));
const height = Number(arg('height', '900'));
const dpr = Number(arg('dpr', '1'));
const settle = Number(arg('settle', '800'));
const clickSelector = arg('click', '');
const api = arg('api', 'http://localhost:8080/api/v1');
const account = arg('account', 'admin');
const password = arg('password', '123456');
const companyId = arg('company', 'c0000000-0000-0000-0000-000000000001');

fs.mkdirSync(outDir, { recursive: true });

async function login() {
  const res = await fetch(`${api}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ account, password, companyId }),
  });
  const body = await res.json().catch(() => null);
  const token = body?.data?.token;
  if (!res.ok || !token) {
    throw new Error(`登录失败 HTTP ${res.status}: ${JSON.stringify(body)}`);
  }
  return token;
}

const browser = await chromium.launch({ channel: process.env.E2E_CHANNEL || 'chrome' });

async function shot(url, name, { auth = false, spaPath = null } = {}) {
  const context = await browser.newContext({
    viewport: { width, height },
    deviceScaleFactor: dpr,
    reducedMotion: 'reduce',
  });

  if (auth) {
    const token = await login();
    await context.addCookies([
      { name: 'token', value: token, url: new URL(url).origin, httpOnly: true, sameSite: 'Strict' },
    ]);
  }

  const page = await context.newPage();
  if (spaPath) {
    const origin = new URL(url).origin;
    await page.goto(`${origin}/`, { waitUntil: 'networkidle', timeout: 60_000 });
    await page.evaluate(p => {
      window.history.pushState({}, '', p);
      window.dispatchEvent(new PopStateEvent('popstate'));
      window.scrollTo({ top: 0 });
    }, spaPath);
  } else {
    await page.goto(url, { waitUntil: 'networkidle', timeout: 60_000 });
  }

  if (clickSelector) {
    await page.click(clickSelector, { timeout: 15_000 });
    await page.waitForTimeout(400);
  }

  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(settle);
  const file = path.join(outDir, `${name}.png`);
  await page.screenshot({ path: file, animations: 'disabled' });
  await context.close();
  return file;
}

const oursFile = await shot(ours, 'ours', { auth: true });
const refFile = await shot(ref, 'ref', { spaPath: new URL(ref).pathname });
await browser.close();

const a = PNG.sync.read(fs.readFileSync(oursFile));
const b = PNG.sync.read(fs.readFileSync(refFile));

if (a.width !== b.width || a.height !== b.height) {
  console.error(`尺寸不一致：ours ${a.width}x${a.height} vs ref ${b.width}x${b.height}`);
  process.exit(1);
}

const diff = new PNG({ width: a.width, height: a.height });
const changed = pixelmatch(a.data, b.data, diff.data, a.width, a.height, {
  threshold: 0.12,
  includeAA: false,
});
fs.writeFileSync(path.join(outDir, 'diff.png'), PNG.sync.write(diff));

const ratio = changed / (a.width * a.height);
const pct = (ratio * 100).toFixed(2);
console.log(`像素差异：${changed} / ${a.width * a.height} = ${pct}%（阈值 ${(threshold * 100).toFixed(2)}%）`);
console.log(`产物：${outDir}/{ours,ref,diff}.png`);

process.exit(ratio <= threshold ? 0 : 1);
