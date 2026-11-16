#!/bin/bash
# ============================================================
# demo-verify.sh — 演示数据验收
#
# 用法（仓库根目录）：bash scripts/demo-verify.sh
#
# 检查两类事情：
#   1. 真实接口：demo_admin 登录后，各章节依赖的读接口有数据
#   2. 真实约束：报告引用的段落与引文必须能在 review_segment 原文中找到
#
# 任一失败退出码非 0。
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
set -a
# shellcheck disable=SC1091
source "$ROOT/.env"
set +a

API="${API:-http://localhost:8080/api/v1}"
COMPANY_ID="d0000000-0000-0000-0000-000000000001"
ACCOUNT="demo_admin"
PASSWORD="123456"

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_DATABASE="${PG_DATABASE:-aiextract}"
export PGPASSWORD="$PG_PASSWORD"

fail() {
  echo "FAIL  $1"
  [ -n "${2:-}" ] && echo "      $2"
  exit 1
}

pass() {
  echo "ok    $1"
}

echo "== 1. 登录 =="
LOGIN=$(curl -s -X POST "$API/auth/login" -H 'Content-Type: application/json' \
  -d "{\"account\":\"$ACCOUNT\",\"password\":\"$PASSWORD\",\"companyId\":\"$COMPANY_ID\"}")
TOKEN=$(echo "$LOGIN" | jq -r '.data.token // empty')
[ -n "$TOKEN" ] || fail "demo_admin 登录失败" "$(echo "$LOGIN" | head -c 300)"
pass "demo_admin 登录"

get() {
  curl -s -H "Authorization: Bearer $TOKEN" "$API$1"
}

assert() {
  local json="$1" expr="$2" name="$3"
  echo "$json" | jq -e "$expr" >/dev/null 2>&1 || fail "$name" "$(echo "$json" | head -c 300)"
  pass "$name"
}

echo "== 2. 经验与分身 =="
SKILLS=$(get "/skills/list?page=1&size=20")
assert "$SKILLS" '.code == 200 and (.data.content | length) >= 1' "分身列表非空"
assert "$SKILLS" '[.data.content[] | select(.status == "published")] | length >= 1' "存在已发布分身"

echo "== 3. 销售复盘 =="
REVIEWS=$(get "/sales/reviews?page=1&size=20")
assert "$REVIEWS" '.code == 200 and .data.total >= 3' "复盘列表 >= 3"

DETAIL=$(get "/sales/reviews/d5000000-0000-0000-0000-000000000001")
assert "$DETAIL" '.code == 200 and .data.session.customerName == "华星科技"' "华星科技复盘可读"
assert "$DETAIL" '.data.session.status == "report_ready"' "华星科技状态 report_ready"
assert "$DETAIL" '(.data.segments | length) >= 8' "华星科技段落 >= 8"

REPORTS=$(get "/sales/reviews/d5000000-0000-0000-0000-000000000001/reports")
assert "$REPORTS" '.code == 200 and (.data | length) >= 1 and .data[0].status == "ready"' "报告已生成"
assert "$REPORTS" '.data[0].payload.nextSteps | length >= 1' "报告含下一步"

GEN=$(get "/sales/reviews/d5000000-0000-0000-0000-000000000003/reports")
assert "$GEN" '.code == 200 and .data[0].status == "generating"' "星河制造处于生成中"

echo "== 4. 线索与任务 =="
LEAD=$(get "/sales/leads/d6000000-0000-0000-0000-000000000001")
assert "$LEAD" '.code == 200 and .data.summary.name == "云海集团"' "线索可读"
assert "$LEAD" '(.data.contacts | length) == 2' "线索含 2 个联系人"
assert "$LEAD" '(.data.activities | length) >= 3' "线索时间线 >= 3"

TASKS=$(get "/sales/tasks?page=1&size=20")
assert "$TASKS" '.code == 200 and .data.total >= 3' "任务 >= 3"

echo "== 5. 教练 =="
LEDGER=$(get "/sales/coach/ledger?page=1&size=20")
assert "$LEDGER" '.code == 200 and .data.total >= 1' "教练台账非空"

echo "== 6. 培训 =="
PLANS=$(get "/admin/training/plans?page=1&size=20")
assert "$PLANS" '.code == 200 and .data.total >= 1' "培训计划存在"
COHORTS=$(get "/admin/training/cohorts?page=1&size=20")
assert "$COHORTS" '.code == 200 and .data.total >= 2' "培训批次 >= 2"

echo "== 7. 报告引用必须命中原文 =="
BAD_QUOTES=$(psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DATABASE" -tAc "
WITH ev AS (
  SELECT r.id AS report_id, e->>'segmentId' AS seg_id, e->>'quote' AS quote
    FROM public.review_report r
    CROSS JOIN LATERAL jsonb_array_elements(COALESCE(r.payload->'strengths','[]'::jsonb)) p
    CROSS JOIN LATERAL jsonb_array_elements(COALESCE(p->'evidence','[]'::jsonb)) e
   WHERE r.company_id = '$COMPANY_ID'
  UNION ALL
  SELECT r.id, e->>'segmentId', e->>'quote'
    FROM public.review_report r
    CROSS JOIN LATERAL jsonb_array_elements(COALESCE(r.payload->'weaknesses','[]'::jsonb)) p
    CROSS JOIN LATERAL jsonb_array_elements(COALESCE(p->'evidence','[]'::jsonb)) e
   WHERE r.company_id = '$COMPANY_ID'
  UNION ALL
  SELECT r.id, e->>'segmentId', e->>'quote'
    FROM public.review_report r
    CROSS JOIN LATERAL jsonb_array_elements(COALESCE(r.payload->'risks','[]'::jsonb)) p
    CROSS JOIN LATERAL jsonb_array_elements(COALESCE(p->'evidence','[]'::jsonb)) e
   WHERE r.company_id = '$COMPANY_ID'
  UNION ALL
  SELECT r.id, e->>'segmentId', e->>'quote'
    FROM public.review_report r
    CROSS JOIN LATERAL jsonb_array_elements(COALESCE(r.payload->'customerSignals','[]'::jsonb)) p
    CROSS JOIN LATERAL jsonb_array_elements(COALESCE(p->'evidence','[]'::jsonb)) e
   WHERE r.company_id = '$COMPANY_ID'
  UNION ALL
  SELECT r.id, e->>'segmentId', e->>'quote'
    FROM public.review_report r
    CROSS JOIN LATERAL jsonb_array_elements(COALESCE(r.payload->'nextSteps','[]'::jsonb)) p
    CROSS JOIN LATERAL jsonb_array_elements(COALESCE(p->'evidence','[]'::jsonb)) e
   WHERE r.company_id = '$COMPANY_ID'
)
SELECT COUNT(*)
  FROM ev
  LEFT JOIN public.review_segment s ON s.id::text = ev.seg_id
 WHERE s.id IS NULL
    OR (ev.quote IS NOT NULL AND ev.quote <> ''
        AND position(regexp_replace(ev.quote, '\s', '', 'g')
                     in regexp_replace(s.text, '\s', '', 'g')) = 0);
")
[ "$BAD_QUOTES" = "0" ] || fail "有 $BAD_QUOTES 条引用无法在原文中命中" "检查 review_report.payload"
pass "所有引用均命中段落原文"

echo
echo "全部通过：演示数据可用。"
