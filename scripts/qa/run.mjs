#!/usr/bin/env node
/**
 * 黑盒验收 runner：权限矩阵 / 黄金链路 / 回归集 / UI 编排。
 *
 *   node scripts/qa/run.mjs --setup
 *   node scripts/qa/run.mjs --suite=permissions
 *   node scripts/qa/run.mjs --suite=golden --base=https://admin.loomforge.cn
 *   node scripts/qa/run.mjs --profile=release
 *   node scripts/qa/run.mjs --suite=regression --case=REG-MAT-01
 *
 * 安全：默认只读；golden/regression 需要写入，profile=release 内部开启。
 * DB 断言通过 ssh 在服务器本机执行，不在本地连接生产库。
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const QA_DIR = path.join(ROOT, 'scripts/qa');
const REPORTS = path.join(ROOT, 'reports/qa');

// ---------- env ----------
function loadEnvFile(file) {
  if (!fs.existsSync(file)) return {};
  const out = {};
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const s = line.trim();
    if (!s || s.startsWith('#')) continue;
    const i = s.indexOf('=');
    if (i < 0) continue;
    let v = s.slice(i + 1).trim();
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) v = v.slice(1, -1);
    out[s.slice(0, i).trim()] = v;
  }
  return out;
}
const fileEnv = loadEnvFile(path.join(ROOT, '.qa.env'));
const env = { ...fileEnv, ...process.env };

const args = process.argv.slice(2);
const arg = (name, def = '') => {
  const hit = args.find(a => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : def;
};
const has = name => args.includes(`--${name}`);

const PROFILE = arg('profile');
const SUITE = arg('suite');
const CASE = arg('case');
const BASE = arg('base', env.QA_BASE || 'https://admin.loomforge.cn').replace(/\/$/, '');
const API = `${BASE}/api/v1`;
const COMPANY_CODE = env.QA_COMPANY_CODE || 'QATEST01';
const ACCOUNTS = {
  super_admin: env.QA_SUPER || 'qa_super',
  company_admin: env.QA_ADMIN || 'qa_admin',
  employee: env.QA_EMP || 'qa_emp',
  coach: env.QA_COACH || 'qa_coach',
  c_user: env.QA_C_USER || 'qa_c_user',
  c_guest: env.QA_C_GUEST || 'qa_c_guest',
  c_partner: env.QA_C_PARTNER || 'qa_c_partner',
};
const PASSWORD = env.QA_PASSWORD || '';
const SSH_HOST = env.QA_SSH_HOST || 'mindforge';
const DB_ENV = env.QA_DB_ENV || '/opt/mindforge/config/backend.env';
const ALLOW_LLM = env.QA_ALLOW_LLM === '1' || PROFILE === 'nightly';

const RUN_ID = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
const PREFIX = `QA-${RUN_ID}-`;
let allowWrite = env.QA_ALLOW_WRITE === '1' || PROFILE === 'release' || PROFILE === 'nightly';

const report = { runId: RUN_ID, base: BASE, profile: PROFILE || SUITE || 'permissions', startedAt: new Date().toISOString(), suites: [], cases: [] };
let blocked = 0;

// ---------- utils ----------
const sleep = ms => new Promise(r => setTimeout(r, ms));
const nowStamp = () => new Date().toISOString().replace(/[:.]/g, '-');
function die(msg) { console.error(`\nQA ERROR: ${msg}`); process.exit(2); }
function log(msg) { console.log(msg); }

function substitute(value, ctx) {
  if (typeof value === 'string') {
    return value.replace(/\$\{([a-zA-Z0-9_]+)\}/g, (_, k) => (ctx[k] == null ? '' : String(ctx[k])));
  }
  if (Array.isArray(value)) return value.map(v => substitute(v, ctx));
  if (value && typeof value === 'object') {
    const out = {};
    // key 也参与替换：考试答案需要 {"${questionId}": "..."} 这种动态键
    for (const [k, v] of Object.entries(value)) out[substitute(k, ctx)] = substitute(v, ctx);
    return out;
  }
  return value;
}

function jsonPath(obj, expr) {
  if (!expr) return obj;
  let cur = obj;
  for (const part of expr.split('.')) {
    if (cur == null) return undefined;
    const m = part.match(/^([^\[]*)(?:\[(\d+)\])?$/);
    if (!m) return undefined;
    if (m[1]) cur = cur[m[1]];
    if (m[2] != null && cur != null) cur = cur[Number(m[2])];
  }
  return cur;
}

async function api(method, pathname, { token, body, timeout = 20000, headers = {} } = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const res = await fetch(`${API}${pathname}`, {
      method,
      headers: {
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: controller.signal,
    });
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* html or sse */ }
    return { status: res.status, text, json };
  } finally {
    clearTimeout(timer);
  }
}

async function sse(pathname, { token, body, timeout = 90000, headers = {} } = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const res = await fetch(`${API}${pathname}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    const reader = res.body?.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    const events = [];
    if (reader) {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        const lines = buf.split('\n');
        buf = lines.pop() || '';
        for (const line of lines) {
          const t = line.trim();
          if (!t.startsWith('data:')) continue;
          const payload = t.slice(5).trim();
          if (!payload || payload === '[DONE]') continue;
          try { events.push(JSON.parse(payload)); } catch { events.push({ raw: payload }); }
        }
      }
    }
    return { status: res.status, events };
  } finally {
    clearTimeout(timer);
  }
}

async function login(account, { fresh = true, companyId, companyCode, cEnd = false } = {}) {
  if (!PASSWORD) die('QA_PASSWORD 未配置（先跑 --setup 或填写 .qa.env）');
  const cacheKey = `${cEnd ? 'C' : (companyId || companyCode || COMPANY_CODE)}:${account}`;
  if (!fresh && tokenCache[cacheKey]) return tokenCache[cacheKey];
  // C 端账号走 /c/auth/login（无企业上下文）；B 端可指定其他企业用于跨租户用例
  if (cEnd) {
    const res = await api('POST', '/c/auth/login', { body: { account, password: PASSWORD } });
    const token = res.json?.data?.token;
    if (res.status !== 200 || !token) {
      throw new Error(`C 端登录失败 ${account}: HTTP ${res.status} ${res.json?.message || res.text.slice(0, 120)}`);
    }
    tokenCache[cacheKey] = token;
    return token;
  }
  const res = await api('POST', '/auth/login', {
    body: companyId
      ? { companyId, account, password: PASSWORD }
      : { companyCode: companyCode || COMPANY_CODE, account, password: PASSWORD },
  });
  const token = res.json?.data?.token;
  if (res.status !== 200 || !token) {
    throw new Error(`登录失败 ${account}: HTTP ${res.status} ${res.json?.message || res.text.slice(0, 120)}`);
  }
  tokenCache[cacheKey] = token;
  return token;
}
const tokenCache = {};

/** 轮询 DB 直到取值为期望值之一；用于等待异步任务（评测批次、报告生成）到终态。 */
async function awaitDb(spec, ctx) {
  const until = (Array.isArray(spec.until) ? spec.until : [spec.until]).map(String);
  let got = '';
  for (let n = 0; n < (spec.maxAttempts || 30); n++) {
    got = db(spec.sql, ctx);
    if (until.includes(got)) return got;
    await sleep(spec.intervalMs || 5000);
  }
  throw new Error(`DB 轮询超时：${spec.sql}\n最后=${got} 期望 ${until.join('/')}`);
}

/** 按步骤声明取 token：as / asAccount + 企业或 C 端登录方式。 */
async function tokenFor(spec, ctx) {
  const opts = {
    // 默认复用 token：每步重新登录会触发限流；登出/改密步骤会显式清空缓存
    fresh: spec.fresh === true,
    companyId: spec.companyId ? substitute(spec.companyId, ctx) : undefined,
    companyCode: spec.companyCode ? substitute(spec.companyCode, ctx) : undefined,
    cEnd: spec.cEnd != null ? !!spec.cEnd : String(spec.as || '').startsWith('c_'),
  };
  if (spec.as === 'anonymous') return undefined;
  if (spec.asAccount) return login(substitute(spec.asAccount, ctx), opts);
  if (spec.as) return login(ACCOUNTS[spec.as] || spec.as, opts);
  return undefined;
}

// DB 断言：ssh 到服务器本机执行，SQL 用 base64 传输避免引号问题
function db(sql, vars = {}) {
  const rendered = String(sql).replace(/(?<!:):([a-zA-Z0-9_]+)/g, (m, k) => {
    if (vars[k] == null) return m;
    return `'${String(vars[k]).replace(/'/g, "''")}'`;
  });
  const b64 = Buffer.from(rendered).toString('base64');
  const remote = `set -a; . ${DB_ENV}; set +a; export PGPASSWORD="$PG_PASSWORD"; echo ${b64} | base64 -d | psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DATABASE" -v ON_ERROR_STOP=1 -tA`;
  const out = execFileSync('ssh', ['-o', 'BatchMode=yes', SSH_HOST, remote], { encoding: 'utf8', timeout: 30000 });
  return out.trim();
}

function sshSqlFile(file, vars = {}) {
  const sql = fs.readFileSync(file, 'utf8');
  const shq = s => `'${String(s).replace(/'/g, `'\\''`)}'`;
  const varArgs = Object.entries(vars).map(([k, v]) => `-v ${k}=${shq(v)}`).join(' ');
  const b64 = Buffer.from(sql).toString('base64');
  const remote = `set -a; . ${DB_ENV}; set +a; export PGPASSWORD="$PG_PASSWORD"; echo ${b64} | base64 -d | psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DATABASE" -v ON_ERROR_STOP=1 ${varArgs}`;
  try {
    return execFileSync('ssh', ['-o', 'BatchMode=yes', SSH_HOST, remote], { encoding: 'utf8', timeout: 120000 });
  } catch (e) {
    throw new Error(`SQL 执行失败（${path.basename(file)}）：${(e.stderr || e.message || '').toString().slice(0, 800)}`);
  }
}

function saveReport() {
  fs.mkdirSync(REPORTS, { recursive: true });
  const dir = path.join(REPORTS, nowStamp());
  fs.mkdirSync(dir, { recursive: true });
  const summary = renderSummary();
  fs.writeFileSync(path.join(dir, 'summary.md'), summary);
  fs.writeFileSync(path.join(dir, 'result.json'), JSON.stringify(report, null, 2));
  return dir;
}

function renderSummary() {
  const lines = [];
  lines.push(`# QA 运行报告 ${report.runId}`, '');
  lines.push(`- 环境：${report.base}`);
  lines.push(`- Profile：${report.profile}`);
  lines.push(`- 开始：${report.startedAt}`, '');
  lines.push('| Suite | Passed | Warned | Failed | Skipped |', '|---|---:|---:|---:|---:|');
  for (const s of report.suites) {
    lines.push(`| ${s.name} | ${s.passed} | ${s.warned || 0} | ${s.failed} | ${s.skipped} |`);
  }
  const failed = report.cases.filter(c => c.status === 'fail');
  lines.push('', '## 失败用例', '');
  if (!failed.length) lines.push('无');
  for (const c of failed) {
    lines.push(`### ${c.id} (${c.suite})`, '');
    lines.push(`- 期望：${c.expected ?? ''}`);
    lines.push(`- 实际：${c.actual ?? ''}`);
    if (c.detail) lines.push(`- 详情：${String(c.detail).slice(0, 500)}`);
    lines.push('');
  }
  const warned = report.cases.filter(c => c.status === 'warn');
  lines.push('', '## 已知告警（⚠ [known]，不阻断发版）', '');
  if (!warned.length) lines.push('无');
  for (const c of warned) {
    lines.push(`### ${c.id} (${c.suite})`, '');
    if (c.knownIssue) lines.push(`- 已知问题：${c.knownIssue}`);
    if (c.expected !== undefined) lines.push(`- 期望：${c.expected}`);
    if (c.actual !== undefined) lines.push(`- 实际：${c.actual}`);
    if (c.detail && c.detail !== 'success') lines.push(`- 详情：${String(c.detail).slice(0, 500)}`);
    lines.push('- 转正条件：修复后删除该用例的 `knownIssue` 字段，重新跑一次即为硬门禁。');
    lines.push('');
  }
  return lines.join('\n');
}

// ---------- permissions ----------
async function runPermissions() {
  const file = path.join(QA_DIR, 'cases/permissions.json');
  if (!fs.existsSync(file)) die(`缺少 ${file}`);
  const cases = JSON.parse(fs.readFileSync(file, 'utf8'));
  const suite = { name: 'permissions', passed: 0, warned: 0, failed: 0, skipped: 0 };
  report.suites.push(suite);

  for (const c of cases) {
    if (CASE && c.id !== CASE) continue;
    const role = c.role;
    let token;
    try {
      // 同角色复用 token：53 条权限用例若每条 fresh 登录会打满账号限流（15 分钟 60 次）
      token = role === 'anonymous' ? undefined
        : await login(ACCOUNTS[role] || role, { fresh: false, cEnd: String(role).startsWith('c_') });
    } catch (e) {
      suite.skipped++; report.cases.push({ id: c.id, suite: 'permissions', status: 'skip', detail: `登录不可用：${e.message}` });
      continue;
    }
    const res = await api(c.method || 'GET', c.path, { token });
    const expected = Array.isArray(c.expect) ? c.expect : [c.expect];
    const ok = expected.includes(res.status);
    const warn = !ok && !!c.knownIssue;
    const item = {
      id: c.id, suite: 'permissions', role, method: c.method || 'GET', path: c.path,
      expected: expected.join('/'), actual: res.status,
      status: ok ? 'pass' : (warn ? 'warn' : 'fail'),
      detail: ok ? '' : (res.json?.message || res.text.slice(0, 200)),
      note: c.note || '', knownIssue: c.knownIssue || null,
    };
    report.cases.push(item);
    if (ok) suite.passed++;
    else if (warn) suite.warned++;
    else suite.failed++;
    const mark = ok ? '✓' : (warn ? '⚠' : '✗');
    log(`  ${mark} ${c.id} ${role} ${c.method || 'GET'} ${c.path} -> ${res.status} (期望 ${expected.join('/')})${warn ? ' [known]' : ''}`);
  }
  return suite;
}

// ---------- case runner ----------
function loadCases(kind) {
  const dir = path.join(QA_DIR, 'cases', kind);
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir).filter(f => f.endsWith('.json')).flatMap(f => {
    const arr = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
    return Array.isArray(arr) ? arr : [arr];
  });
}

function checkExpect(expect = {}, res) {
  const problems = [];
  const statuses = Array.isArray(expect.status) ? expect.status : (expect.status != null ? [expect.status] : []);
  if (statuses.length && !statuses.includes(res.status)) problems.push(`status ${res.status} 不在 ${statuses.join('/')}`);
  if (expect.json) {
    for (const [expr, want] of Object.entries(expect.json)) {
      const got = jsonPath(res.json, expr);
      if (JSON.stringify(got) !== JSON.stringify(want)) problems.push(`${expr}=${JSON.stringify(got)}，期望 ${JSON.stringify(want)}`);
    }
  }
  // 列表去重断言：{"uniqueBy": {"data.content": "id"}}
  if (expect.uniqueBy) {
    for (const [expr, field] of Object.entries(expect.uniqueBy)) {
      const arr = jsonPath(res.json, expr);
      if (!Array.isArray(arr)) { problems.push(`${expr} 不是数组`); continue; }
      const seen = new Set();
      for (const item of arr) {
        const v = jsonPath(item, field);
        if (seen.has(v)) { problems.push(`${expr} 存在重复 ${field}=${v}`); break; }
        seen.add(v);
      }
    }
  }
  return problems;
}

async function runCase(c, suite) {
  if (c.llm && !ALLOW_LLM) {
    suite.skipped++; report.cases.push({ id: c.id, suite: suite.name, status: 'skip', detail: 'llm 用例默认跳过（QA_ALLOW_LLM=1 开启）' });
    log(`  - ${c.id} skip (llm)`);
    return;
  }
  if (!allowWrite && c.steps?.some(s => (s.api || '').split(' ')[0] !== 'GET')) {
    suite.skipped++; report.cases.push({ id: c.id, suite: suite.name, status: 'skip', detail: '写用例需要 QA_ALLOW_WRITE=1' });
    log(`  - ${c.id} skip (write)`);
    return;
  }

  const ctx = { runId: RUN_ID, shortId: RUN_ID.slice(-6), prefix: PREFIX, qaPassword: PASSWORD };
  const evidence = [];
  // 登出/改密后的 token 在本用例内仍需复用（用例常断言旧 token 已失效），
  // 缓存清空推迟到用例结束，避免污染后续用例
  let staleTokenCache = false;
  try {
    for (const [i, step] of (c.steps || []).entries()) {
      // 步骤前置等待：等异步任务（评测批次、报告生成）到终态再继续
      if (step.await) {
        const got = await awaitDb(step.await, ctx);
        evidence.push({ step: i + 1, await: step.await.sql, got });
      }
      // 测试夹具：为阈值类用例补齐前置数据（如发布门槛的颗粒数），不属于产品行为断言
      if (step.dbExec) {
        db(step.dbExec.sql, ctx);
        evidence.push({ step: i + 1, dbExec: step.dbExec.sql.slice(0, 300) });
      }
      const method = (step.api || 'GET').split(' ')[0];
      const pathname = substitute((step.api || 'GET').split(' ').slice(1).join(' '), ctx);
      const body = step.body === undefined ? undefined : substitute(step.body, ctx);
      const token = step.token ? substitute(step.token, ctx)
        : step.tokenFrom ? ctx[step.tokenFrom] : await tokenFor(step, ctx);
      if (step.sse) {
        const res = await sse(pathname, {
          token, body, timeout: step.timeout || 90000, headers: step.headers || {},
        });
        const types = res.events.map(e => e.type);
        evidence.push({ step: i + 1, sse: true, path: pathname, status: res.status, types, count: res.events.length });
        const wantStatus = step.expect?.status;
        if (wantStatus != null) {
          const arr = Array.isArray(wantStatus) ? wantStatus : [wantStatus];
          if (!arr.includes(res.status)) throw new Error(`步骤 ${i + 1} SSE status=${res.status}，期望 ${arr.join('/')}`);
        }
        const wantTypes = step.expect?.eventTypes || [];
        const missing = wantTypes.filter(t => !types.includes(t));
        if (missing.length) throw new Error(`步骤 ${i + 1} SSE 缺少事件：${missing.join(',')}（实际 ${types.join(',')}）`);
        if (step.expect?.contentNonEmpty && !res.events.some(e => e.type === 'content' && String(e.content || '').trim())) {
          throw new Error(`步骤 ${i + 1} SSE 没有非空 content`);
        }
        // 溯源断言：source 事件必须带非空 grainIds（逗号分隔字符串）
        if (step.expect?.sourceGrainIds) {
          const source = res.events.filter(e => e.type === 'source');
          if (!source.some(e => e.grainIds && String(e.grainIds).trim())) {
            throw new Error(`步骤 ${i + 1} SSE source 事件没有 grainIds（source=${source.length}）`);
          }
        }
        // 知识/案例引用断言：source 事件里必须出现指定 sourceType
        if (step.expect?.sourceType) {
          const want = substitute(step.expect.sourceType, ctx);
          if (!res.events.some(e => e.type === 'source' && String(e.sourceType || '') === want)) {
            throw new Error(`步骤 ${i + 1} SSE source 未命中 sourceType=${want}`);
          }
        }
        if (step.expect?.sourceTitleContains) {
          const want = substitute(step.expect.sourceTitleContains, ctx);
          if (!res.events.some(e => e.type === 'source' && String(e.sourceTitle || '').includes(want))) {
            throw new Error(`步骤 ${i + 1} SSE source 标题不含 ${want}`);
          }
        }
        // 把 source 事件字段存进上下文，供后续 DB 回查
        if (step.saveSse) {
          const fields = Object.entries(step.saveSse.fields || {});
          // 从后往前找「带目标字段」的事件：同类型 source 可能有多条（经验/知识/案例），
          // 只按类型取最后一条会拿到没有 grainIds 的知识溯源事件
          const event = [...res.events].reverse().find(e =>
            e.type === (step.saveSse.from || 'source')
            && fields.every(([, field]) => e[field] != null && String(e[field]).trim() !== ''));
          if (!event) {
            throw new Error(`步骤 ${i + 1} SSE 没有带 ${fields.map(([k]) => k).join('/')} 的 ${step.saveSse.from || 'source'} 事件`);
          }
          for (const [k, field] of fields) ctx[k] = event[field];
        }
        ctx.__events = res.events;
        continue;
      }
      const res = await api(method, pathname, {
        token, body, timeout: step.timeout || 20000, headers: step.headers || {},
      });
      evidence.push({ step: i + 1, method, path: pathname, status: res.status, body: res.json ?? res.text.slice(0, 300) });
      const problems = checkExpect(step.expect, res);
      if (problems.length) throw new Error(`步骤 ${i + 1} ${method} ${pathname}: ${problems.join('；')}`);
      if (step.save) {
        for (const [k, expr] of Object.entries(step.save)) ctx[k] = jsonPath(res.json, expr);
      }
      // 登出/改密会让该账号的旧 token 失效：本用例内继续复用（后续步骤常断言 401），
      // 用例结束后统一清缓存
      if (pathname.includes('/auth/logout') || pathname.includes('/auth/change-password')) {
        staleTokenCache = true;
      }
      if (step.sleepMs) await sleep(step.sleepMs);
    }

    for (const a of (c.assertions || [])) {
      if (a.type === 'dbPoll') {
        const got = await awaitDb(a, ctx);
        evidence.push({ assertion: 'dbPoll', sql: a.sql, got });
      } else if (a.type === 'db') {
        const got = db(a.sql, ctx);
        const want = String(substitute(a.expect, ctx));
        const ok = a.op === 'contains' ? got.includes(want)
          : a.op === 'gte' ? Number(got) >= Number(want)
          : a.op === 'lte' ? Number(got) <= Number(want)
          : got === want;
        if (!ok) throw new Error(`DB 断言失败：${a.sql}\n实际=${got} 期望=${want}`);
        evidence.push({ assertion: 'db', sql: a.sql, got });
      } else if (a.type === 'http') {
        const token = a.tokenFrom ? ctx[a.tokenFrom] : await tokenFor(a, ctx);
        const res = await api(a.method || 'GET', substitute(a.path, ctx), { token });
        const problems = checkExpect(a.expect, res);
        if (problems.length) throw new Error(`HTTP 断言失败：${problems.join('；')}`);
        evidence.push({ assertion: 'http', path: a.path, status: res.status });
      } else if (a.type === 'sse') {
        const types = (ctx.__events || []).map(e => e.type);
        const missing = (a.has || []).filter(t => !types.includes(t));
        if (missing.length) throw new Error(`SSE 断言缺少事件：${missing.join(',')}`);
        evidence.push({ assertion: 'sse', types });
      }
    }

    suite.passed++;
    report.cases.push({ id: c.id, suite: suite.name, status: 'pass', evidence });
    log(`  ✓ ${c.id}`);
  } catch (e) {
    if (c.knownIssue) {
      suite.warned++;
      report.cases.push({ id: c.id, suite: suite.name, status: 'warn', detail: e.message, knownIssue: c.knownIssue, evidence });
      log(`  ⚠ ${c.id}: ${e.message} [known]`);
    } else {
      suite.failed++;
      report.cases.push({ id: c.id, suite: suite.name, status: 'fail', detail: e.message, evidence });
      log(`  ✗ ${c.id}: ${e.message}`);
    }
  } finally {
    if (staleTokenCache) {
      for (const k of Object.keys(tokenCache)) delete tokenCache[k];
    }
  }
}

async function runCases(kind) {
  const cases = loadCases(kind);
  if (!cases.length) { log(`  （${kind} 暂无用例）`); return { name: kind, passed: 0, failed: 0, skipped: 0 }; }
  const suite = { name: kind, passed: 0, warned: 0, failed: 0, skipped: 0 };
  report.suites.push(suite);
  for (const c of cases) {
    if (CASE && c.id !== CASE) continue;
    await runCase(c, suite);
  }
  return suite;
}

// ---------- setup ----------
function qaEnvTemplate() {
  return [
    '# QA 黑盒测试环境（不要提交到仓库）',
    `QA_BASE=${BASE}`,
    'QA_COMPANY_CODE=QATEST01',
    'QA_ADMIN=qa_admin',
    'QA_EMP=qa_emp',
    'QA_COACH=qa_coach',
    `QA_PASSWORD=${PASSWORD || crypto.randomBytes(12).toString('base64url')}`,
    'QA_SSH_HOST=mindforge',
    'QA_DB_ENV=/opt/mindforge/config/backend.env',
    'QA_ALLOW_WRITE=0',
    'QA_ALLOW_LLM=0',
    '',
  ].join('\n');
}

function bcryptHash(password) {
  try {
    const out = execFileSync('python3', ['-c', 'import bcrypt,sys;print(bcrypt.hashpw(sys.argv[1].encode(), bcrypt.gensalt(rounds=10)).decode())', password], { encoding: 'utf8' });
    return out.trim();
  } catch {
    const out = execFileSync('htpasswd', ['-bnBC', '10', '', password], { encoding: 'utf8' });
    return out.trim().replace(/^:/, '');
  }
}

async function setup() {
  const envFile = path.join(ROOT, '.qa.env');
  if (!fs.existsSync(envFile)) {
    fs.writeFileSync(envFile, qaEnvTemplate(), { mode: 0o600 });
    log(`已生成 .qa.env（已加入 .gitignore），请确认后重新执行 --setup`);
    return 0;
  }
  const password = loadEnvFile(envFile).QA_PASSWORD || PASSWORD;
  if (!password) die('.qa.env 缺少 QA_PASSWORD');
  const hash = bcryptHash(password);
  const seed = path.join(QA_DIR, 'seed/qa-seed.sql');
  log('种子执行第 1 次...');
  sshSqlFile(seed, { qa_password_hash: hash });
  log('种子执行第 2 次（幂等校验）...');
  sshSqlFile(seed, { qa_password_hash: hash });
  const counts = db(`select
    (select count(*) from public.company where id='0a000000-0000-0000-0000-000000000001')
    ||'/'||(select count(*) from public."user" where company_id='0a000000-0000-0000-0000-000000000001')
    ||'/'||(select count(*) from public.skill where company_id='0a000000-0000-0000-0000-000000000001')`);
  log(`QA 租户对象 company/user/skill = ${counts}`);
  log('cleanup 空跑（无 runId 数据）...');
  sshSqlFile(path.join(QA_DIR, 'seed/qa-cleanup.sql'), { qa_run_id: 'setup-noop' });
  log('setup 完成');
  return 0;
}

// ---------- static / ui / holdout ----------
function runStatic() {
  const steps = [
    ['frontend lint', 'npm', ['run', 'lint'], path.join(ROOT, 'frontend')],
    ['tsc', 'npx', ['tsc', '--noEmit'], path.join(ROOT, 'frontend')],
    ['architecture', 'bash', ['scripts/check-architecture.sh'], ROOT],
    // 代码审核：后端 517 个单测，发版前必须全绿（不再 -DskipTests）
    ['backend test', 'mvn', ['test', '-q'], path.join(ROOT, 'backend')],
  ];
  const suite = { name: 'static', passed: 0, warned: 0, failed: 0, skipped: 0 };
  report.suites.push(suite);
  for (const [name, cmd, argv, cwd] of steps) {
    const r = spawnSync(cmd, argv, { cwd, encoding: 'utf8', timeout: 600000 });
    const ok = r.status === 0;
    ok ? suite.passed++ : suite.failed++;
    report.cases.push({ id: name, suite: 'static', status: ok ? 'pass' : 'fail', detail: ok ? '' : (r.stdout + r.stderr).slice(-800) });
    log(`  ${ok ? '✓' : '✗'} ${name}`);
  }
  return suite;
}

function runUi() {
  const suite = { name: 'ui', passed: 0, warned: 0, failed: 0, skipped: 0 };
  report.suites.push(suite);
  const config = path.join(ROOT, 'frontend/playwright.config.ts');
  if (!fs.existsSync(config)) {
    suite.skipped++; report.cases.push({ id: 'ui', suite: 'ui', status: 'skip', detail: '没有 Playwright 配置' });
    return suite;
  }
  const r = spawnSync('npx', ['playwright', 'test',
    'e2e/qa.ui.spec.ts', 'e2e/qa.interaction.spec.ts', '--reporter=json'], {
    cwd: path.join(ROOT, 'frontend'),
    env: {
      ...process.env,
      E2E_BASE_URL: BASE,
      E2E_NO_WEBSERVER: '1',
      E2E_ACCOUNT: ACCOUNTS.company_admin,
      E2E_EMP: ACCOUNTS.employee,
      E2E_EMP_ID: '0a000000-0000-0000-0000-000000000012',
      E2E_PASSWORD: PASSWORD,
      E2E_COMPANY_CODE: COMPANY_CODE,
      E2E_COMPANY_ID: '0a000000-0000-0000-0000-000000000001',
      E2E_API_URL: API,
      E2E_RUN_ID: RUN_ID,
    },
    encoding: 'utf8', timeout: 900000,
  });
  // JSON reporter 给出逐条计数，不再把 6 条 UI 用例算成 1 条
  let stats = null;
  try { stats = JSON.parse(r.stdout).stats; } catch { /* 解析失败走兜底 */ }
  const passed = stats ? (stats.expected || 0) : (r.status === 0 ? 1 : 0);
  const failed = stats ? (stats.unexpected || 0) + (stats.flaky || 0) : (r.status === 0 ? 0 : 1);
  const skipped = stats ? (stats.skipped || 0) : 0;
  suite.passed += passed;
  suite.failed += failed;
  suite.skipped += skipped;
  report.cases.push({
    id: 'ui', suite: 'ui',
    status: failed ? 'fail' : 'pass',
    actual: `${passed} passed / ${failed} failed / ${skipped} skipped`,
    detail: failed ? (r.stdout + r.stderr).slice(-3000) : '',
  });
  log(`  ${failed ? '✗' : '✓'} ui (playwright) ${passed} passed / ${failed} failed`);
  return suite;
}

async function runHoldout() {
  const dir = env.QA_HOLDOUT_DIR;
  const suite = { name: 'holdout', passed: 0, warned: 0, failed: 0, skipped: 0 };
  report.suites.push(suite);
  if (!dir || !fs.existsSync(dir)) {
    suite.skipped++; report.cases.push({ id: 'holdout', suite: 'holdout', status: 'skip', detail: '未配置 QA_HOLDOUT_DIR' });
    log('  - holdout skip');
    return suite;
  }
  // 把 holdout 目录当 golden 读取
  const files = fs.readdirSync(dir).filter(f => f.endsWith('.json'));
  if (!files.length) { suite.skipped++; return suite; }
  for (const f of files) {
    const arr = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
    for (const c of (Array.isArray(arr) ? arr : [arr])) {
      if (CASE && c.id !== CASE) continue;
      await runCase(c, suite);
    }
  }
  return suite;
}

// ---------- main ----------
async function main() {
  log(`QA 运行 ${RUN_ID}  ${BASE}`);
  if (has('setup')) return await setup();

  let suites = [];
  if (PROFILE === 'quick') {
    suites.push(runStatic());
    suites.push(await runPermissions());
  } else if (PROFILE === 'release' || PROFILE === 'nightly') {
    suites.push(runStatic());
    suites.push(await runPermissions());
    suites.push(await runCases('golden'));
    suites.push(await runCases('regression'));
    suites.push(runUi());
    suites.push(await runHoldout());
  } else if (SUITE === 'permissions') {
    suites.push(await runPermissions());
  } else if (SUITE === 'golden' || SUITE === 'regression') {
    suites.push(await runCases(SUITE));
  } else if (SUITE === 'ui') {
    suites.push(runUi());
  } else if (SUITE === 'holdout') {
    suites.push(await runHoldout());
  } else {
    die('用法：--setup | --suite=permissions|golden|regression|ui|holdout | --profile=quick|release|nightly');
  }

  // 清理：只有写套件跑过才清理
  // UI 交互用例也会通过 API 造数据（培训计划 / 知识），同样按 runId 清理
  const wrote = suites.some(s => ['golden', 'regression', 'holdout', 'ui'].includes(s.name) && s.passed + s.failed > 0);
  if (wrote) {
    log('清理本次 runId 数据...');
    try {
      sshSqlFile(path.join(QA_DIR, 'seed/qa-cleanup.sql'), { qa_run_id: RUN_ID });
      log('清理完成，残留校验通过');
    } catch (e) {
      log(`清理失败（需手工处理）：${e.message}`);
      blocked++;
    }
  }

  report.finishedAt = new Date().toISOString();
  const dir = saveReport();
  log('\n结果');
  for (const s of report.suites) log(`  ${s.name.padEnd(12)} pass=${s.passed} warn=${s.warned || 0} fail=${s.failed} skip=${s.skipped}`);
  const failed = report.suites.reduce((n, s) => n + s.failed, 0);
  log(`报告：${path.relative(ROOT, path.join(dir, 'summary.md'))}`);
  if (blocked) { log('BLOCKED（环境/清理问题）'); return 2; }
  if (failed) { log('FAILED'); return 1; }
  log('PASSED');
  return 0;
}

main().then(code => process.exit(code)).catch(e => {
  console.error(`\nQA ERROR: ${e.message}`);
  if (e.stack) console.error(e.stack.split('\n').slice(0, 4).join('\n'));
  process.exit(2);
});
