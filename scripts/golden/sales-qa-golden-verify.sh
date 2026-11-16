#!/usr/bin/env bash
# 销售问答黄金集 —— 只读校验（DB + API）。
#
#   bash scripts/golden/sales-qa-golden-verify.sh
#   bash scripts/golden/sales-qa-golden-verify.sh --with-copy        # 额外验证复制链路（会清理）
#   bash scripts/golden/sales-qa-golden-verify.sh --with-demo-reset  # 额外证明 demo-reset 不影响黄金集
#
# 默认只读，不创建运行、不消耗 token。
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"

if [ -f "$ROOT/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
fi

DATASET_ID="e0000000-0000-0000-0000-000000000001"
VERSION_ID="e0100000-0000-0000-0000-000000000001"
API="${GOLDEN_API:-http://localhost:8080/api/v1}"
DEMO_COMPANY="${GOLDEN_COMPANY_ID:-d0000000-0000-0000-0000-000000000001}"
ACCOUNT="${GOLDEN_ACCOUNT:-demo_admin}"
PASSWORD="${GOLDEN_PASSWORD:-123456}"
COMPANY_CODE="${GOLDEN_COMPANY_CODE:-DEMO2026}"

export PGPASSWORD="${PG_PASSWORD:-}"
PSQL=(psql -h "${PG_HOST:-localhost}" -p "${PG_PORT:-5432}" -U "${PG_USER:-$USER}" \
  -d "${PG_DATABASE:-aiextract}" -v ON_ERROR_STOP=1 -tA)

FAIL=0
ok()  { printf 'ok    %s\n' "$1"; }
bad() { printf 'FAIL  %s\n' "$1"; FAIL=1; }
q()   { "${PSQL[@]}" -c "$1" | tr -d '[:space:]'; }

WITH_COPY=0
WITH_DEMO_RESET=0
for arg in "$@"; do
  case "$arg" in
    --with-copy) WITH_COPY=1 ;;
    --with-demo-reset) WITH_DEMO_RESET=1 ;;
    *) echo "未知参数: $arg"; exit 2 ;;
  esac
done

echo "== 1. 生成物 =="
if node "$DIR/build-sales-qa-golden.mjs" --check >/dev/null 2>&1; then
  ok "items.json 与 seed SQL 一致"
else
  bad "items.json 与 seed SQL 不一致，请运行 node scripts/golden/build-sales-qa-golden.mjs"
fi

echo "== 2. 数据库 =="
v=$(q "SELECT count(*) FROM evaluation_dataset
        WHERE id='$DATASET_ID' AND company_id IS NULL AND purpose='acceptance'
          AND status='published' AND current_version=1 AND item_count=50;")
[ "$v" = "1" ] && ok "dataset 为平台公共 / published / v1 / 50 题" \
  || bad "dataset 状态不正确（count=${v}）"

v=$(q "SELECT count(*) FROM evaluation_dataset_version
        WHERE id='$VERSION_ID' AND dataset_id='$DATASET_ID' AND version=1
          AND status='published' AND item_count=50 AND frozen_at IS NOT NULL;")
[ "$v" = "1" ] && ok "version 1 已发布并冻结" || bad "version 行不正确（count=${v}）"

v=$(q "SELECT count(*) FROM evaluation_item
        WHERE dataset_id='$DATASET_ID' AND version=1 AND status='active';")
[ "$v" = "50" ] && ok "50 条 active 题目" || bad "active 题目数=$v"

v=$(q "SELECT count(DISTINCT scene_tag) FROM evaluation_item
        WHERE dataset_id='$DATASET_ID' AND version=1 AND status='active';")
[ "$v" = "10" ] && ok "10 个场景" || bad "场景数=$v"

v=$(q "SELECT count(*) FROM (
          SELECT scene_tag FROM evaluation_item
           WHERE dataset_id='$DATASET_ID' AND version=1 AND status='active'
           GROUP BY scene_tag HAVING count(*)<>5) s;")
[ "$v" = "0" ] && ok "每个场景恰好 5 题" || bad "有 $v 个场景题量不是 5"

v=$(q "SELECT count(DISTINCT order_no) FROM evaluation_item
        WHERE dataset_id='$DATASET_ID' AND version=1 AND status='active';")
[ "$v" = "50" ] && ok "order_no 1..50 唯一" || bad "order_no 唯一数=$v"

v=$(q "SELECT count(*) FROM (
          SELECT question FROM evaluation_item
           WHERE dataset_id='$DATASET_ID' AND version=1 AND status='active'
           GROUP BY question HAVING count(*)>1) s;")
[ "$v" = "0" ] && ok "题干无重复" || bad "有 $v 组重复题干"

v=$(q "SELECT count(*) FROM evaluation_item
        WHERE dataset_id='$DATASET_ID' AND version=1 AND status='active'
          AND company_id IS NULL
          AND expected_points IS NOT NULL AND jsonb_typeof(expected_points)='array'
          AND jsonb_array_length(expected_points)>=2
          AND jsonb_typeof(forbidden_points)='array'
          AND jsonb_typeof(retrieval_expected)='array';")
[ "$v" = "50" ] && ok "JSONB 字段类型与长度正确" || bad "JSONB 校验通过数=$v"

v=$(q "SELECT count(*) FROM evaluation_item
        WHERE dataset_id='$DATASET_ID' AND version=1 AND status='active'
          AND citation_requirement IS NOT NULL;")
[ "$v" = "10" ] && ok "10 条要求引用" || bad "引用题数=$v"

v=$(q "SELECT count(*) FROM evaluation_item
        WHERE dataset_id='$DATASET_ID' AND version=1 AND status='active'
          AND jsonb_array_length(retrieval_expected)>0;")
[ "$v" = "10" ] && ok "10 条带检索期望" || bad "检索期望题数=$v"

echo "== 3. API =="
TOKEN=$(curl -s -X POST "$API/auth/login" -H 'Content-Type: application/json' \
  -d "{\"companyCode\":\"$COMPANY_CODE\",\"account\":\"$ACCOUNT\",\"password\":\"$PASSWORD\"}" \
  | jq -r '.data.token // empty')
if [ -z "$TOKEN" ]; then
  bad "登录失败（${ACCOUNT}）"
else
  ok "登录成功"

  RESP=$(curl -s -G -H "Authorization: Bearer $TOKEN" \
    --data-urlencode "keyword=销售问答黄金集" --data "page=1" --data "size=20" \
    "$API/admin/evaluations/datasets")
  if echo "$RESP" | jq -e --arg id "$DATASET_ID" \
      '.code==200 and ([.data.content[] | select(.id==$id)] | length)==1' >/dev/null; then
    ok "列表可见黄金集"
  else
    bad "列表中没有黄金集"
  fi

  if echo "$RESP" | jq -e --arg id "$DATASET_ID" \
      '[.data.content[] | select(.id==$id)][0] |
       .companyId==null and .status=="published" and .currentVersion==1 and .itemCount==50' >/dev/null; then
    ok "列表字段为平台公共 / published / v1 / 50"
  else
    bad "列表字段不正确"
  fi

  ITEMS=$(curl -s -H "Authorization: Bearer $TOKEN" \
    "$API/admin/evaluations/datasets/$DATASET_ID/items?version=1&page=1&size=100")
  if echo "$ITEMS" | jq -e '.code==200 and .data.total==50' >/dev/null; then
    ok "题目接口返回 50 条"
  else
    bad "题目接口返回不是 50"
  fi

  if echo "$ITEMS" | jq -e \
      '[.data.content[] | (.expectedPoints != null and ((.expectedPoints|fromjson|length) >= 2))] | all' >/dev/null; then
    ok "每条题目都有至少 2 个预期要点"
  else
    bad "存在缺少预期要点的题目"
  fi
fi

echo "== 4. 复制链路（可选） =="
if [ "$WITH_COPY" = "1" ]; then
  COPY_ID=""
  cleanup_copy() {
    if [ -n "$COPY_ID" ]; then
      "${PSQL[@]}" -c "DELETE FROM evaluation_item WHERE dataset_id='$COPY_ID';
        DELETE FROM evaluation_dataset_version WHERE dataset_id='$COPY_ID';
        DELETE FROM evaluation_generation_job WHERE dataset_id='$COPY_ID';
        DELETE FROM evaluation_dataset WHERE id='$COPY_ID' AND company_id IS NOT NULL;" >/dev/null || true
    fi
  }
  trap cleanup_copy EXIT

  COPY=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" \
    "$API/admin/evaluations/datasets/$DATASET_ID/copy")
  COPY_ID=$(echo "$COPY" | jq -r '.data.id // empty')
  if [ -n "$COPY_ID" ]; then
    ok "复制成功（${COPY_ID}）"
    echo "$COPY" | jq -e --arg c "$DEMO_COMPANY" \
      '.data.companyId==$c and .data.status=="draft"' >/dev/null \
      && ok "复制件属于演示企业且为 draft" || bad "复制件企业/状态不正确"
    COPY_ITEMS=$(curl -s -H "Authorization: Bearer $TOKEN" \
      "$API/admin/evaluations/datasets/$COPY_ID/items?version=1&page=1&size=100")
    echo "$COPY_ITEMS" | jq -e '.data.total==50' >/dev/null \
      && ok "复制件含 50 题" || bad "复制件题目数不是 50"
  else
    bad "复制失败：$(echo "$COPY" | jq -r '.message // .' )"
  fi
else
  echo "skip  （加 --with-copy 运行）"
fi

echo "== 5. demo-reset 兼容性（可选） =="
if [ "$WITH_DEMO_RESET" = "1" ]; then
  BEFORE=$(q "SELECT count(*) FROM evaluation_item
               WHERE dataset_id='$DATASET_ID' AND version=1 AND status='active';")
  "${PSQL[@]}" -f "$ROOT/scripts/demo-reset.sql" >/dev/null
  AFTER=$(q "SELECT count(*) FROM evaluation_item
              WHERE dataset_id='$DATASET_ID' AND version=1 AND status='active';")
  if [ "$BEFORE" = "50" ] && [ "$AFTER" = "50" ]; then
    ok "demo-reset 前后黄金集均为 50"
  else
    bad "demo-reset 影响了黄金集（before=$BEFORE after=${AFTER}）"
  fi
  # demo-reset 会删演示租户数据，恢复演示环境
  "${PSQL[@]}" -f "$ROOT/scripts/demo-seed.sql" >/dev/null
  "${PSQL[@]}" -f "$ROOT/scripts/tour-seed.sql" >/dev/null
  ok "已恢复演示数据"
else
  echo "skip  （加 --with-demo-reset 运行）"
fi

echo
if [ "$FAIL" = "0" ]; then
  echo "ALL CHECKS PASSED"
else
  echo "CHECKS FAILED"
  exit 1
fi
