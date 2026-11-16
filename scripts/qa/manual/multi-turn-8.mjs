#!/usr/bin/env node
/**
 * 8 轮多轮对话人工脚本的自动执行器（语义判定仍由人做）。
 *
 *   node scripts/qa/manual/multi-turn-8.mjs
 *
 * 依赖：.qa.env 里的 QA_PASSWORD；已发布分身 0a000000-...-0032；线上后端可用。
 * 输出：reports/qa/manual/multi-turn-8-<时间戳>.json（每轮原始回答，供人工按观察点打分）。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(DIR, '../../..');
const BASE = (process.env.QA_BASE || 'https://admin.loomforge.cn').replace(/\/$/, '');
const API = `${BASE}/api/v1`;
const COMPANY_CODE = process.env.QA_COMPANY_CODE || 'QATEST01';
const ACCOUNT = process.env.QA_EMP || 'qa_emp';
const SKILL_ID = process.env.MULTI_TURN_SKILL || '0a000000-0000-0000-0000-000000000032';

function loadEnvFile(file) {
  const out = {};
  if (!fs.existsSync(file)) return out;
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const m = line.match(/^([A-Z0-9_]+)=(.*)$/);
    if (m) out[m[1]] = m[2];
  }
  return out;
}

const PASSWORD = process.env.QA_PASSWORD || loadEnvFile(path.join(ROOT, '.qa.env')).QA_PASSWORD;
if (!PASSWORD) {
  console.error('QA_PASSWORD 未配置（复制 .qa.env.example 并填写）');
  process.exit(2);
}

const ROUNDS = [
  '客户说我们的价格比竞品高 20%，而且预算还没批下来，我该怎么回应？',
  '如果客户坚持必须先降价再谈，我上一轮说的分期交付方案还适用吗？',
  '客户内部还要走采购流程，大概两周，这段时间我该做什么？',
  '如果竞品在这两周里给了更低报价，我怎么把讨论拉回价值？',
  '帮我把前几轮聊的内容整理成一个跟进计划，按时间排一下。',
  '采购流程如果提前到一周，计划哪里要改？',
  '客户现在说预算批了，但要求账期 90 天，怎么谈？',
  '回到第一轮：当时预算没批，现在批了，我的方案要改哪几处？',
];

async function login() {
  const res = await fetch(`${API}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ companyCode: COMPANY_CODE, account: ACCOUNT, password: PASSWORD }),
  });
  const text = await res.text();
  const json = text ? JSON.parse(text) : {};
  if (res.status !== 200) throw new Error(`登录失败 ${res.status}: ${text.slice(0, 200)}`);
  return json.data.token;
}

async function chat(token, message, conversationId) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 180000);
  try {
    const res = await fetch(`${API}/skills/${SKILL_ID}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(conversationId ? { message, conversationId } : { message }),
      signal: controller.signal,
    });
    if (res.status !== 200) throw new Error(`SSE status=${res.status}: ${(await res.text()).slice(0, 200)}`);
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    let content = '';
    let convId = conversationId || null;
    const types = [];
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
        let evt;
        try { evt = JSON.parse(payload); } catch { continue; }
        types.push(evt.type);
        if (evt.type === 'meta' && evt.conversationId) convId = evt.conversationId;
        if (evt.type === 'content' && evt.content) content += evt.content;
      }
    }
    return { content: content.trim(), conversationId: convId, types: [...new Set(types)] };
  } finally {
    clearTimeout(timer);
  }
}

const token = await login();
const results = [];
let conversationId = null;
for (const [i, message] of ROUNDS.entries()) {
  const t0 = Date.now();
  const r = await chat(token, message, conversationId);
  conversationId = r.conversationId || conversationId;
  results.push({ round: i + 1, message, answer: r.content, ms: Date.now() - t0 });
  console.log(`\n=== 第 ${i + 1} 轮（${Date.now() - t0}ms）===\n${r.content}`);
}

fs.mkdirSync(path.join(ROOT, 'reports/qa/manual'), { recursive: true });
const out = path.join(ROOT, `reports/qa/manual/multi-turn-8-${new Date().toISOString().replace(/[:.]/g, '-')}.json`);
fs.writeFileSync(out, JSON.stringify({ skillId: SKILL_ID, conversationId, rounds: results }, null, 2));
console.log(`\n会话：${conversationId}\n记录：${path.relative(ROOT, out)}`);
