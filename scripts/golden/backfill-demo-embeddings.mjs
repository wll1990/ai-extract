#!/usr/bin/env node
/**
 * 为本地演示分身的颗粒回填向量。
 *
 *   node scripts/golden/backfill-demo-embeddings.mjs
 *
 * 背景：demo-seed.sql 只插文本不插向量，导致黄金集回归时 RAG 检索不到任何颗粒。
 * 文本拼接规则与 DashScopeEmbeddingService.grainToText 完全一致：
 *   scene_tag + " " + scene_description + " " + applicable_condition
 */
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

function loadEnv() {
  const env = {};
  const file = path.join(ROOT, '.env');
  if (!fs.existsSync(file)) return env;
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const m = line.match(/^([A-Z0-9_]+)=(.*)$/);
    if (m) env[m[1]] = m[2];
  }
  return env;
}

const env = loadEnv();
const KEY = process.env.DASHSCOPE_API_KEY || env.DASHSCOPE_API_KEY || env.QWEN_API_KEY;
if (!KEY) {
  console.error('缺少 DASHSCOPE_API_KEY / QWEN_API_KEY（.env）');
  process.exit(2);
}

const PSQL = ['psql', '-h', env.PG_HOST || 'localhost', '-p', env.PG_PORT || '5432',
  '-U', env.PG_USER || process.env.USER, '-d', env.PG_DATABASE || 'aiextract', '-tA'];

function sql(query) {
  return execFileSync(PSQL[0], [...PSQL.slice(1), '-c', query], {
    encoding: 'utf8',
    env: { ...process.env, PGPASSWORD: env.PG_PASSWORD || '' },
  }).trim();
}

const rows = sql(`select id || E'\\t' || coalesce(scene_tag,'') || E'\\t' || coalesce(scene_description,'') || E'\\t' || coalesce(applicable_condition,'')
from public.experience_grain where embedding is null and status = 'active'`)
  .split('\n').filter(Boolean);

if (!rows.length) {
  console.log('没有需要回填的颗粒');
  process.exit(0);
}

const items = rows.map(line => {
  const [id, sceneTag, sceneDesc, condition] = line.split('\t');
  const text = [sceneTag, sceneDesc, condition].join(' ').trim() || '未分类';
  return { id, text };
});

// DashScope 单次最多 10 条
const BATCH = 10;
let ok = 0;
for (let start = 0; start < items.length; start += BATCH) {
  const batch = items.slice(start, start + BATCH);
  const res = await fetch('https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${KEY}` },
    body: JSON.stringify({ model: 'text-embedding-v4', input: batch.map(i => i.text), dimensions: 1024 }),
  });
  const json = await res.json();
  if (!res.ok) {
    console.error(`DashScope ${res.status}: ${JSON.stringify(json).slice(0, 300)}`);
    process.exit(1);
  }
  for (const [i, item] of batch.entries()) {
    const vec = json.data?.[i]?.embedding;
    if (!Array.isArray(vec) || vec.length !== 1024) {
      console.error(`跳过 ${item.id}：向量缺失/维度不对`);
      continue;
    }
    sql(`update public.experience_grain set embedding = '[${vec.join(',')}]'::vector where id = '${item.id}'`);
    ok++;
  }
}

console.log(`回填完成：${ok}/${items.length}`);
