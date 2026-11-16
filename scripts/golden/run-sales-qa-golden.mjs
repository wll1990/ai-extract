#!/usr/bin/env node
/**
 * 研发侧黄金回归 —— 用演示分身跑一遍评测中心的销售问答黄金集。
 *
 *   node scripts/golden/run-sales-qa-golden.mjs --dry-run   # 只校验登录与数据集
 *   node scripts/golden/run-sales-qa-golden.mjs             # 真实运行，消耗 token
 *
 * 依赖：黄金集已 seed；后端、数据库、LLM 可用；演示分身 d3000000-...0001 已发布。
 * 会写入运行历史。默认不进 CI，按需手动/夜间执行。
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const DIR = path.dirname(fileURLToPath(import.meta.url));
const SOURCE = path.join(DIR, 'sales-qa-golden.items.json');

const API = process.env.GOLDEN_API || 'http://localhost:8080/api/v1';
const COMPANY_CODE = process.env.GOLDEN_COMPANY_CODE || 'DEMO2026';
const ACCOUNT = process.env.GOLDEN_ACCOUNT || 'demo_admin';
const PASSWORD = process.env.GOLDEN_PASSWORD || '123456';
const SKILL_ID = process.env.GOLDEN_SKILL_ID || 'd3000000-0000-0000-0000-000000000001';
const DATASET_ID = process.env.GOLDEN_DATASET_ID || 'e0000000-0000-0000-0000-000000000001';
const MIN_SCORE = Number(process.env.GOLDEN_MIN_SCORE || 60);
const POLL_MS = Number(process.env.GOLDEN_POLL_MS || 5000);
const TIMEOUT_MS = Number(process.env.GOLDEN_TIMEOUT_MS || 15 * 60 * 1000);

const DRY_RUN = process.argv.includes('--dry-run');
const runIdArg = process.argv.find(a => a.startsWith('--run-id='));
const EXISTING_RUN_ID = process.env.GOLDEN_RUN_ID || (runIdArg ? runIdArg.split('=')[1] : '');
const TERMINAL = new Set(['completed', 'partial_failed', 'failed', 'cancelled']);

const source = JSON.parse(fs.readFileSync(SOURCE, 'utf8'));
const byId = new Map(source.items.map(i => [i.id, i]));
const critical = source.items.filter(i => i.critical);

let token = '';

async function api(pathname, { method = 'GET', body } = {}) {
  const res = await fetch(`${API}${pathname}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json;
  try { json = text ? JSON.parse(text) : {}; } catch { json = { raw: text }; }
  if (!res.ok || json.code !== 200) {
    const msg = json?.message || json?.raw || res.statusText;
    throw new Error(`${method} ${pathname} -> HTTP ${res.status}: ${msg}`);
  }
  return json.data;
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

function jsonField(v) {
  if (!v) return {};
  if (typeof v === 'object') return v;
  try { return JSON.parse(v); } catch { return {}; }
}

async function main() {
  console.log(`API:       ${API}`);
  console.log(`dataset:   ${DATASET_ID}`);
  console.log(`skill:     ${SKILL_ID}`);
  console.log(`threshold: ${MIN_SCORE}`);

  const login = await api('/auth/login', {
    method: 'POST',
    body: { companyCode: COMPANY_CODE, account: ACCOUNT, password: PASSWORD },
  });
  token = login?.token;
  if (!token) throw new Error('登录成功但没有 token');

  const ds = await api(`/admin/evaluations/datasets/${DATASET_ID}`);
  if (ds.companyId !== null) throw new Error(`黄金集不是平台公共（companyId=${ds.companyId}）`);
  if (ds.status !== 'published') throw new Error(`黄金集状态不是 published（${ds.status}）`);
  if (ds.currentVersion !== 1 || ds.itemCount !== 50) {
    throw new Error(`黄金集版本/题量不正确（v${ds.currentVersion}, ${ds.itemCount} 题）`);
  }

  const items = await api(`/admin/evaluations/datasets/${DATASET_ID}/items?version=1&page=1&size=100`);
  if (items.total !== 50) throw new Error(`题目接口返回 ${items.total} 条，应为 50`);

  console.log(`ok  数据集：${ds.name}（${ds.itemCount} 题，${critical.length} 条关键题）`);

  if (DRY_RUN) {
    console.log('dry-run：不创建运行。');
    return 0;
  }

  let run;
  if (EXISTING_RUN_ID) {
    run = await api(`/admin/evaluations/runs/${EXISTING_RUN_ID}`);
    console.log(`复用运行:  ${run.id}（当前 ${run.status}）`);
  } else {
    run = await api('/admin/evaluations/runs', {
      method: 'POST',
      body: {
        datasetId: DATASET_ID,
        datasetVersion: 1,
        skillId: SKILL_ID,
        purpose: 'acceptance',
        idempotencyKey: `golden-${Date.now()}-${crypto.randomUUID().slice(0, 8)}`,
      },
    });
    console.log(`run:       ${run.id}`);
  }

  const started = Date.now();
  let current = run;
  while (!TERMINAL.has(current.status)) {
    if (Date.now() - started > TIMEOUT_MS) {
      throw new Error(`运行超时（${Math.round(TIMEOUT_MS / 1000)}s），最后状态 ${current.status}`);
    }
    process.stdout.write(`  ${current.status} ${current.completedItems || 0}/${current.totalItems || 0}\r`);
    await sleep(POLL_MS);
    current = await api(`/admin/evaluations/runs/${run.id}`);
  }
  process.stdout.write('\n');

  const detail = await api(`/admin/evaluations/runs/${run.id}`);
  const runItems = await api(`/admin/evaluations/runs/${run.id}/items?page=1&size=100`);
  const rows = runItems.content || [];
  const rowByItem = new Map(rows.map(r => [r.itemId, r]));

  const infra = [];
  const content = [];

  if (detail.status === 'failed' || detail.status === 'cancelled') {
    infra.push(`运行状态为 ${detail.status}`);
  }
  if (detail.totalItems !== 50) infra.push(`totalItems=${detail.totalItems}`);
  if (detail.errorItems > 0) {
    const errs = rows.filter(r => r.status === 'error');
    const counts = new Map();
    for (const r of errs) {
      const msg = (r.errorMessage || '未知错误').slice(0, 120);
      counts.set(msg, (counts.get(msg) || 0) + 1);
    }
    const top = [...counts.entries()].sort((a, b) => b[1] - a[1])[0];
    infra.push(`errorItems=${detail.errorItems}` + (top ? `；多数错误：${top[1]} 条「${top[0]}」` : ''));
  }
  if (typeof detail.scoreAvg !== 'number' || Number.isNaN(detail.scoreAvg)) {
    content.push('scoreAvg 缺失');
  } else if (detail.scoreAvg < MIN_SCORE) {
    content.push(`scoreAvg=${detail.scoreAvg} < ${MIN_SCORE}`);
  }

  for (const c of critical) {
    const row = rowByItem.get(c.id);
    if (!row) { content.push(`关键题 ${c.id} 没有运行结果`); continue; }
    if (row.status === 'error') {
      infra.push(`关键题因执行错误未完成：${c.id}（${c.sceneTag}）`);
      continue;
    }
    if (row.verdict !== 'pass') {
      content.push(`关键题未通过：${c.id}（${c.sceneTag} / ${row.verdict || row.status}）`);
    }
    const rule = jsonField(row.ruleCheckResult);
    const hits = rule.forbidden_hits || rule.forbiddenHits || [];
    if (Array.isArray(hits) && hits.length > 0) {
      content.push(`关键题命中禁止项：${c.id} → ${hits.join('；')}`);
    }
  }

  const failedRows = rows.filter(r => r.status !== 'error' && r.verdict === 'fail');

  const byScene = new Map();
  for (const row of rows) {
    const meta = byId.get(row.itemId);
    if (!meta) continue;
    const s = byScene.get(meta.sceneTag) || { pass: 0, fail: 0, other: 0 };
    if (row.verdict === 'pass') s.pass += 1;
    else if (row.verdict === 'fail') s.fail += 1;
    else s.other += 1;
    byScene.set(meta.sceneTag, s);
  }

  console.log('\n结果');
  console.log(`  状态      ${detail.status}`);
  console.log(`  总分      ${detail.scoreAvg}`);
  console.log(`  通过/失败  ${detail.passedItems}/${detail.failedItems}（待定 ${detail.undeterminedItems}，错误 ${detail.errorItems}）`);
  console.log('  分场景');
  for (const [scene, s] of [...byScene.entries()].sort()) {
    console.log(`    ${scene.padEnd(6, '　')}  pass=${s.pass} fail=${s.fail} other=${s.other}`);
  }

  if (failedRows.length) {
    console.log('\n内容失败项');
    for (const row of failedRows) {
      const meta = byId.get(row.itemId);
      const score = jsonField(row.aiScore);
      console.log(`  - ${meta?.id || row.itemId}（${meta?.sceneTag || '?'}）`);
      if (meta?.question) console.log(`    题干：${meta.question}`);
      if (score.reason) console.log(`    原因：${String(score.reason).slice(0, 200)}`);
    }
  }

  if (infra.length) {
    console.log('\n执行错误（非评分失败）');
    infra.forEach(p => console.log(`  - ${p}`));
    console.log('\nGOLDEN RUN BLOCKED（环境/额度/网络）');
    return 2;
  }

  if (content.length) {
    console.log('\n未通过项');
    content.forEach(p => console.log(`  - ${p}`));
    console.log('\nGOLDEN RUN FAILED');
    return 1;
  }

  console.log('\nGOLDEN RUN PASSED');
  return 0;
}

main()
  .then(code => process.exit(code))
  .catch(err => {
    console.error(`\nGOLDEN RUN ERROR: ${err.message}`);
    process.exit(1);
  });
