#!/usr/bin/env node
/**
 * 销售问答黄金集 —— 校验 + 生成种子 SQL。
 *
 *   node scripts/golden/build-sales-qa-golden.mjs            # 生成 SQL
 *   node scripts/golden/build-sales-qa-golden.mjs --check    # 只校验并与已提交 SQL 比对
 *   node scripts/golden/build-sales-qa-golden.mjs --report   # 打印覆盖矩阵，不写文件
 *
 * 唯一内容源：sales-qa-golden.items.json。SQL 是生成物，不要手改。
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const DIR = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.join(DIR, 'sales-qa-golden.items.json');
const SQL = path.join(DIR, 'sales-qa-golden-seed.sql');

const DATASET_ID = 'e0000000-0000-0000-0000-000000000001';
const VERSION_ID = 'e0100000-0000-0000-0000-000000000001';
const SCENES = ['采购停滞', '切换风险', '价格异议', '竞品', '关键人',
  '异议处理', '方案汇报', '边界', '试用', '续约'];
const ARCHETYPES = ['fact_policy', 'scenario_judgment', 'script', 'boundary_refusal', 'hard_edge'];
const SOURCE_TYPES = ['experience_grain', 'knowledge_entry', 'industry_case', 'review_report', 'domain_config'];

const errors = [];

function fail(msg) {
  errors.push(msg);
}

function isNonEmptyString(v) {
  return typeof v === 'string' && v.trim().length > 0;
}

function isStringArray(v, min, max, label) {
  if (!Array.isArray(v)) { fail(`${label}: 必须是数组`); return; }
  if (v.length < min || v.length > max) fail(`${label}: 长度 ${v.length} 不在 ${min}-${max}`);
  v.forEach((s, i) => {
    if (!isNonEmptyString(s)) fail(`${label}[${i}]: 必须是非空字符串`);
    if (typeof s === 'string' && s.length > 160) fail(`${label}[${i}]: 超过 160 字`);
  });
}

const raw = fs.readFileSync(SRC, 'utf8');
let data;
try {
  data = JSON.parse(raw);
} catch (e) {
  console.error(`JSON 解析失败: ${e.message}`);
  process.exit(1);
}

// ---------- top-level ----------
if (data.schemaVersion !== 1) fail('schemaVersion 必须为 1');
if (!data.release || typeof data.release !== 'object') fail('缺少 release');
if (!data.ids || data.ids.datasetId !== DATASET_ID) fail(`datasetId 必须为 ${DATASET_ID}`);
if (data.ids?.versionId !== VERSION_ID) fail(`versionId 必须为 ${VERSION_ID}`);
if (data.ids?.version !== 1) fail('version 必须为 1');
if (!data.release?.name || !data.release?.description) fail('release.name/description 不能为空');
if (data.release?.purpose !== 'acceptance') fail('purpose 必须为 acceptance');
if (data.release?.publishStatus !== 'published') fail('publishStatus 必须为 published');
if (!['ai_drafted', 'in_review', 'reviewed'].includes(data.release?.reviewStatus)) {
  fail('reviewStatus 必须是 ai_drafted / in_review / reviewed');
}

const weights = data.scoringRule?.weights;
const WEIGHT_KEYS = ['style_score', 'consistency_score', 'behavior_score', 'script_reuse_score'];
if (!weights) {
  fail('缺少 scoringRule.weights');
} else {
  const keys = Object.keys(weights).sort();
  if (JSON.stringify(keys) !== JSON.stringify([...WEIGHT_KEYS].sort())) {
    fail(`scoringRule.weights 必须且只能包含 ${WEIGHT_KEYS.join(', ')}`);
  }
  const sum = WEIGHT_KEYS.reduce((s, k) => s + Number(weights[k] || 0), 0);
  if (Math.abs(sum - 1) > 1e-9) fail(`scoringRule 权重之和为 ${sum}，必须为 1`);
  WEIGHT_KEYS.forEach(k => {
    if (!(Number(weights[k]) > 0)) fail(`scoringRule.weights.${k} 必须大于 0`);
  });
}
if (!isNonEmptyString(data.scoringRule?.rubricVersion)) fail('缺少 rubricVersion');

const items = data.items;
if (!Array.isArray(items) || items.length !== 50) {
  fail(`items 必须恰好 50 条，当前 ${Array.isArray(items) ? items.length : '非数组'}`);
}

// ---------- items ----------
const seenIds = new Set();
const seenOrders = new Set();
const seenQuestions = new Set();

(items || []).forEach((item, idx) => {
  const tag = `items[${idx}] (${item?.id || '?'})`;
  const expectedId = `e0200000-0000-0000-0000-${String(idx + 1).padStart(12, '0')}`;
  if (item.id !== expectedId) fail(`${tag}: id 必须为 ${expectedId}`);
  if (seenIds.has(item.id)) fail(`${tag}: id 重复`);
  seenIds.add(item.id);

  if (item.orderNo !== idx + 1) fail(`${tag}: orderNo 必须为 ${idx + 1}`);
  if (seenOrders.has(item.orderNo)) fail(`${tag}: orderNo 重复`);
  seenOrders.add(item.orderNo);

  if (!isNonEmptyString(item.question)) fail(`${tag}: question 不能为空`);
  if (isNonEmptyString(item.question) && item.question.length > 300) fail(`${tag}: question 超过 300 字`);
  if (isNonEmptyString(item.question)) {
    const q = item.question.trim().replace(/\s+/g, ' ');
    if (seenQuestions.has(q)) fail(`${tag}: question 重复`);
    seenQuestions.add(q);
  }

  if (!SCENES.includes(item.sceneTag)) fail(`${tag}: sceneTag 不在 10 个场景内`);
  if (!ARCHETYPES.includes(item.archetype)) fail(`${tag}: archetype 非法`);
  if (item.archetype && item.sceneTag) {
    const expectCritical = item.archetype === 'fact_policy' || item.archetype === 'boundary_refusal';
    if (item.critical !== expectCritical) fail(`${tag}: critical 与 archetype 不一致`);
  }
  if (item.context !== null && item.context !== undefined && typeof item.context !== 'string') {
    fail(`${tag}: context 必须是 null 或字符串`);
  }
  if (typeof item.context === 'string' && item.context.length > 1000) fail(`${tag}: context 超过 1000 字`);

  isStringArray(item.expectedPoints, 2, 6, `${tag}.expectedPoints`);
  isStringArray(item.forbiddenPoints, 0, 5, `${tag}.forbiddenPoints`);
  isStringArray(item.retrievalExpected, 0, 5, `${tag}.retrievalExpected`);

  if (!isNonEmptyString(item.goldBasis)) fail(`${tag}: goldBasis 不能为空`);
  if (!Array.isArray(item.sourceRefs) || item.sourceRefs.length === 0) {
    fail(`${tag}: sourceRefs 不能为空`);
  } else {
    item.sourceRefs.forEach((r, i) => {
      if (!SOURCE_TYPES.includes(r.type)) fail(`${tag}.sourceRefs[${i}]: type 非法`);
      if (!isNonEmptyString(r.id)) fail(`${tag}.sourceRefs[${i}]: id 不能为空`);
      if (!isNonEmptyString(r.title)) fail(`${tag}.sourceRefs[${i}]: title 不能为空`);
    });
  }

  const cite = item.citationRequirement;
  if (cite !== null && cite !== undefined) {
    if (typeof cite !== 'object') fail(`${tag}: citationRequirement 必须是 null 或对象`);
    if (cite.required !== true) fail(`${tag}: citationRequirement.required 必须为 true`);
    if (!Number.isInteger(cite.minCitations) || cite.minCitations < 1) fail(`${tag}: minCitations 必须 >= 1`);
    if (!Array.isArray(cite.preferredSources) || cite.preferredSources.length === 0) {
      fail(`${tag}: preferredSources 不能为空`);
    }
  }

  if (!Array.isArray(item.reviewNotes)) fail(`${tag}: reviewNotes 必须是数组`);
});

// ---------- coverage ----------
const sceneCount = new Map();
const sceneArch = new Map();
(items || []).forEach(item => {
  sceneCount.set(item.sceneTag, (sceneCount.get(item.sceneTag) || 0) + 1);
  if (!sceneArch.has(item.sceneTag)) sceneArch.set(item.sceneTag, new Set());
  sceneArch.get(item.sceneTag).add(item.archetype);
});
SCENES.forEach(scene => {
  if (sceneCount.get(scene) !== 5) fail(`场景 ${scene} 必须有 5 条，当前 ${sceneCount.get(scene) || 0}`);
  const archs = sceneArch.get(scene) || new Set();
  ARCHETYPES.forEach(a => {
    if (!archs.has(a)) fail(`场景 ${scene} 缺少题型 ${a}`);
  });
});
if (sceneCount.size !== 10) fail(`场景数必须为 10，当前 ${sceneCount.size}`);

const critical = (items || []).filter(i => i.critical).length;
if (critical !== 20) fail(`关键题必须为 20，当前 ${critical}`);
const negative = (items || []).filter(i => Array.isArray(i.forbiddenPoints) && i.forbiddenPoints.length > 0).length;
if (negative < 25) fail(`带禁止项的题必须 >= 25，当前 ${negative}`);
const cited = (items || []).filter(i => i.citationRequirement).length;
if (cited !== 10) fail(`要求引用的题必须为 10，当前 ${cited}`);
const retrieved = (items || []).filter(i => Array.isArray(i.retrievalExpected) && i.retrievalExpected.length > 0).length;
if (retrieved !== 10) fail(`带检索期望的题必须为 10，当前 ${retrieved}`);
(items || []).forEach(i => {
  if (i.citationRequirement && i.archetype !== 'fact_policy') {
    fail(`${i.id}: 只有 fact_policy 可以要求引用`);
  }
  if (i.retrievalExpected?.length && i.archetype !== 'fact_policy') {
    fail(`${i.id}: 只有 fact_policy 可以带检索期望`);
  }
});

// ---------- dollar-quote safety ----------
function checkQuote(v, label) {
  if (typeof v === 'string' && (v.includes('$q$') || v.includes('$j$'))) {
    fail(`${label}: 不能包含 $q$ 或 $j$`);
  }
}
(items || []).forEach((item, idx) => {
  const tag = `items[${idx}]`;
  checkQuote(item.question, `${tag}.question`);
  checkQuote(item.context, `${tag}.context`);
  checkQuote(item.goldBasis, `${tag}.goldBasis`);
  (item.expectedPoints || []).forEach((s, i) => checkQuote(s, `${tag}.expectedPoints[${i}]`));
  (item.forbiddenPoints || []).forEach((s, i) => checkQuote(s, `${tag}.forbiddenPoints[${i}]`));
  (item.retrievalExpected || []).forEach((s, i) => checkQuote(s, `${tag}.retrievalExpected[${i}]`));
});
checkQuote(data.release?.name, 'release.name');
checkQuote(data.release?.description, 'release.description');

function report() {
  console.log(`黄金集: ${items.length} 题 / ${sceneCount.size} 场景 / 关键 ${critical} / 负例 ${negative} / 引用 ${cited}`);
  console.log('\n场景            A事实  B判断  C话术  D边界  E难例  合计');
  SCENES.forEach(scene => {
    const counts = ARCHETYPES.map(a =>
      (items || []).filter(i => i.sceneTag === scene && i.archetype === a).length);
    console.log(`${scene.padEnd(10, '　')}  ${counts.map(c => String(c).padStart(4)).join('  ')}  ${counts.reduce((a, b) => a + b, 0)}`);
  });
}

if (errors.length) {
  console.error(`校验失败，共 ${errors.length} 项：`);
  errors.forEach(e => console.error(`  - ${e}`));
  process.exit(1);
}

const args = process.argv.slice(2);
if (args.includes('--report')) {
  report();
  process.exit(0);
}

// ---------- render SQL ----------
function q(text) {
  if (text === null || text === undefined) return 'NULL';
  return `$q$${text}$q$`;
}

function j(value) {
  if (value === null || value === undefined) return 'NULL';
  return `$j$${JSON.stringify(value)}$j$::jsonb`;
}

const sha = crypto.createHash('sha256').update(raw).digest('hex');
const generatedAt = 'generated by build-sales-qa-golden.mjs';

const header = `-- 销售问答黄金集 v1.0 —— 种子 SQL
-- DO NOT EDIT BY HAND. 源文件: scripts/golden/sales-qa-golden.items.json
-- source-sha256: ${sha}
-- ${generatedAt}
--
-- 幂等：固定 UUID + 先 UPDATE 后 INSERT；版本行用 (dataset_id, version) 唯一键。
-- 评测表没有主键约束，不能用 ON CONFLICT (id)；只操作 dataset ${DATASET_ID}。
\\set ON_ERROR_STOP on

BEGIN;

CREATE TEMP TABLE _golden_item (
    id                   uuid PRIMARY KEY,
    order_no             integer NOT NULL,
    scene_tag            varchar(50) NOT NULL,
    question             text NOT NULL,
    context              text,
    expected_points      jsonb NOT NULL,
    forbidden_points     jsonb NOT NULL,
    citation_requirement jsonb,
    retrieval_expected   jsonb NOT NULL,
    gold_basis           text NOT NULL,
    scoring_rule         jsonb NOT NULL
) ON COMMIT DROP;

INSERT INTO _golden_item VALUES
`;

const valueRows = items.map(item => {
  const scoring = JSON.stringify(data.scoringRule);
  return `  ('${item.id}', ${item.orderNo}, ${q(item.sceneTag)}, ${q(item.question)}, ${q(item.context)}, ` +
    `${j(item.expectedPoints)}, ${j(item.forbiddenPoints)}, ${j(item.citationRequirement)}, ` +
    `${j(item.retrievalExpected)}, ${q(item.goldBasis)}, $j$${scoring}$j$::jsonb)`;
}).join(',\n');

const body = `;

-- 预检：拒绝覆盖企业数据集、已有更高版本、或与运行历史冲突的内容变更
DO $$
DECLARE bad integer;
BEGIN
  IF EXISTS (SELECT 1 FROM evaluation_dataset
              WHERE id = '${DATASET_ID}' AND company_id IS NOT NULL) THEN
    RAISE EXCEPTION '${DATASET_ID} 已存在且属于企业数据，拒绝覆盖';
  END IF;

  IF EXISTS (SELECT 1 FROM evaluation_dataset_version
              WHERE dataset_id = '${DATASET_ID}'
                AND version = 1
                AND id <> '${VERSION_ID}') THEN
    RAISE EXCEPTION '(${DATASET_ID}, version=1) 已被其它版本行占用';
  END IF;

  IF EXISTS (SELECT 1 FROM evaluation_dataset_version
              WHERE dataset_id = '${DATASET_ID}' AND version > 1) THEN
    RAISE EXCEPTION '黄金集已存在更高版本，请升级源版本或先执行 scoped reset';
  END IF;

  SELECT count(*) INTO bad
    FROM _golden_item g
    JOIN evaluation_item i ON i.id = g.id
   WHERE i.dataset_id <> '${DATASET_ID}';
  IF bad > 0 THEN
    RAISE EXCEPTION '% 个黄金集 item id 已属于其它数据集', bad;
  END IF;

  SELECT count(*) INTO bad
    FROM _golden_item g
    JOIN evaluation_item i ON i.id = g.id
   WHERE i.dataset_id = '${DATASET_ID}' AND i.version <> 1;
  IF bad > 0 THEN
    RAISE EXCEPTION '% 个黄金集 item id 已存在于其它版本，拒绝改写', bad;
  END IF;

  SELECT count(*) INTO bad
    FROM _golden_item g
    JOIN evaluation_item i ON i.id = g.id
   WHERE (i.question, i.context, i.expected_points, i.forbidden_points,
          i.citation_requirement, i.retrieval_expected, i.gold_basis,
          i.scene_tag, i.order_no, i.scoring_rule)
         IS DISTINCT FROM
         (g.question, g.context, g.expected_points, g.forbidden_points,
          g.citation_requirement, g.retrieval_expected, g.gold_basis,
          g.scene_tag, g.order_no, g.scoring_rule)
     AND EXISTS (SELECT 1 FROM evaluation_run_item ri WHERE ri.item_id = g.id);
  IF bad > 0 THEN
    RAISE EXCEPTION '% 条黄金题内容有变化但已有运行历史；请创建 v2，不要原地改 v1', bad;
  END IF;
END $$;

UPDATE evaluation_dataset d
   SET name = ${q(data.release.name)},
       description = ${q(data.release.description)},
       purpose = 'acceptance',
       status = 'published',
       current_version = 1,
       item_count = 50,
       updated_at = now()
 WHERE d.id = '${DATASET_ID}'
   AND (d.company_id, d.name, d.description, d.purpose,
        d.status, d.current_version, d.item_count)
       IS DISTINCT FROM
       (NULL, ${q(data.release.name)}, ${q(data.release.description)},
        'acceptance', 'published', 1, 50);

INSERT INTO evaluation_dataset
  (id, company_id, name, description, purpose, status, current_version,
   item_count, created_by, created_at, updated_at)
SELECT '${DATASET_ID}', NULL, ${q(data.release.name)}, ${q(data.release.description)},
       'acceptance', 'published', 1, 50, NULL, now(), now()
 WHERE NOT EXISTS (SELECT 1 FROM evaluation_dataset WHERE id = '${DATASET_ID}');

INSERT INTO evaluation_dataset_version
  (id, dataset_id, version, status, item_count, frozen_at, created_by, created_at)
VALUES
  ('${VERSION_ID}', '${DATASET_ID}', 1, 'published', 50, now(), NULL, now())
ON CONFLICT (dataset_id, version) DO UPDATE SET
  status = 'published',
  item_count = EXCLUDED.item_count,
  frozen_at = COALESCE(evaluation_dataset_version.frozen_at, now())
WHERE evaluation_dataset_version.dataset_id = '${DATASET_ID}'
  AND (evaluation_dataset_version.status, evaluation_dataset_version.item_count,
       evaluation_dataset_version.frozen_at IS NULL)
      IS DISTINCT FROM ('published', EXCLUDED.item_count, false);

UPDATE evaluation_item i
   SET company_id = NULL,
       dataset_id = '${DATASET_ID}',
       version = 1,
       question = g.question,
       context = g.context,
       expected_points = g.expected_points,
       forbidden_points = g.forbidden_points,
       citation_requirement = g.citation_requirement,
       scene_tag = g.scene_tag,
       gold_basis = g.gold_basis,
       scoring_rule = g.scoring_rule,
       retrieval_expected = g.retrieval_expected,
       fact_key = NULL,
       fact_quote = NULL,
       order_no = g.order_no,
       status = 'active',
       updated_at = now()
  FROM _golden_item g
 WHERE i.id = g.id
   AND i.dataset_id = '${DATASET_ID}'
   AND (i.company_id, i.version, i.question, i.context,
        i.expected_points, i.forbidden_points, i.citation_requirement,
        i.scene_tag, i.gold_basis, i.scoring_rule, i.retrieval_expected,
        i.order_no, i.status)
       IS DISTINCT FROM
       (NULL, 1, g.question, g.context, g.expected_points, g.forbidden_points,
        g.citation_requirement, g.scene_tag, g.gold_basis, g.scoring_rule,
        g.retrieval_expected, g.order_no, 'active');

INSERT INTO evaluation_item
  (id, company_id, dataset_id, version, question, context,
   expected_points, forbidden_points, citation_requirement, scene_tag,
   gold_basis, scoring_rule, retrieval_expected, fact_key, fact_quote,
   order_no, status, created_by, created_at, updated_at)
SELECT g.id, NULL, '${DATASET_ID}', 1,
       g.question, g.context, g.expected_points, g.forbidden_points,
       g.citation_requirement, g.scene_tag, g.gold_basis,
       g.scoring_rule, g.retrieval_expected, NULL, NULL,
       g.order_no, 'active', NULL, now(), now()
  FROM _golden_item g
 WHERE NOT EXISTS (SELECT 1 FROM evaluation_item i WHERE i.id = g.id);

-- 源里已删除的题只下线，不物理删除，保证历史运行还能解析 item id
UPDATE evaluation_item i
   SET status = 'deprecated', updated_at = now()
 WHERE i.dataset_id = '${DATASET_ID}'
   AND i.version = 1
   AND i.status = 'active'
   AND i.created_by IS NULL
   AND NOT EXISTS (SELECT 1 FROM _golden_item g WHERE g.id = i.id);

-- 按 active 数重算 item_count（运行/复制只取 active）
UPDATE evaluation_dataset_version v
   SET item_count = (
         SELECT count(*) FROM evaluation_item i
          WHERE i.dataset_id = '${DATASET_ID}' AND i.version = 1 AND i.status = 'active')
 WHERE v.dataset_id = '${DATASET_ID}' AND v.version = 1
   AND v.item_count IS DISTINCT FROM (
         SELECT count(*) FROM evaluation_item i
          WHERE i.dataset_id = '${DATASET_ID}' AND i.version = 1 AND i.status = 'active');

UPDATE evaluation_dataset d
   SET item_count = v.item_count, updated_at = now()
  FROM evaluation_dataset_version v
 WHERE d.id = '${DATASET_ID}' AND v.dataset_id = d.id AND v.version = 1
   AND d.item_count IS DISTINCT FROM v.item_count;

-- 后置断言：任何一项不满足都回滚
DO $$
DECLARE
  active integer;
  ds_ok integer;
  ver_ok integer;
  scene_ok integer;
  bad integer;
BEGIN
  SELECT count(*) INTO active
    FROM evaluation_item
   WHERE dataset_id = '${DATASET_ID}' AND version = 1 AND status = 'active';

  SELECT count(*) INTO ds_ok
    FROM evaluation_dataset
   WHERE id = '${DATASET_ID}' AND company_id IS NULL
     AND purpose = 'acceptance' AND status = 'published'
     AND current_version = 1 AND item_count = active;

  SELECT count(*) INTO ver_ok
    FROM evaluation_dataset_version
   WHERE id = '${VERSION_ID}' AND dataset_id = '${DATASET_ID}'
     AND version = 1 AND status = 'published' AND item_count = active
     AND frozen_at IS NOT NULL;

  SELECT count(*) INTO scene_ok
    FROM (SELECT scene_tag FROM evaluation_item
           WHERE dataset_id = '${DATASET_ID}' AND version = 1 AND status = 'active'
           GROUP BY scene_tag HAVING count(*) = 5) s;

  SELECT count(*) INTO bad
    FROM evaluation_item
   WHERE dataset_id = '${DATASET_ID}' AND version = 1 AND status = 'active'
     AND (company_id IS NOT NULL
          OR question IS NULL OR btrim(question) = ''
          OR expected_points IS NULL
          OR jsonb_typeof(expected_points) <> 'array'
          OR jsonb_array_length(expected_points) < 2
          OR jsonb_typeof(forbidden_points) <> 'array'
          OR jsonb_typeof(retrieval_expected) <> 'array'
          OR order_no NOT BETWEEN 1 AND 50);

  IF active <> 50 OR ds_ok <> 1 OR ver_ok <> 1 OR scene_ok <> 10 OR bad <> 0 THEN
    RAISE EXCEPTION
      '黄金集后置校验失败: active=%, dataset=%, version=%, scenes=%, bad=%',
      active, ds_ok, ver_ok, scene_ok, bad;
  END IF;

  RAISE NOTICE '黄金集种子 OK: dataset=${DATASET_ID}, version=1, active=%, scenes=%',
    active, scene_ok;
END $$;

COMMIT;
`;

const sql = header + valueRows + body;

if (args.includes('--check')) {
  if (!fs.existsSync(SQL)) {
    console.error(`缺少生成物: ${SQL}`);
    process.exit(1);
  }
  const current = fs.readFileSync(SQL, 'utf8');
  if (current !== sql) {
    console.error('SQL 与源文件不一致，请运行 node scripts/golden/build-sales-qa-golden.mjs');
    process.exit(1);
  }
  report();
  console.log(`\nOK SQL 已是最新: ${path.relative(process.cwd(), SQL)}`);
  process.exit(0);
}

const outIdx = args.indexOf('--out');
const outPath = outIdx >= 0 && args[outIdx + 1] ? args[outIdx + 1] : SQL;
const tmp = `${outPath}.tmp`;
fs.writeFileSync(tmp, sql, 'utf8');
fs.renameSync(tmp, outPath);
report();
console.log(`\n已生成: ${path.relative(process.cwd(), outPath)} (sha256 ${sha.slice(0, 12)}…)`);
