#!/usr/bin/env bash
# 批次 0 回归脚本 —— 直连本地后端与数据库，验证已修的 P0。
# 前置：sh dev.sh 已启动；psql 可连接 aiextract。
# 用法：bash scripts/batch0-regression.sh
set -euo pipefail

BASE="http://localhost:8080/api/v1"
DB="${PGDATABASE:-aiextract}"
COMPANY_CODE="${DEMO_COMPANY_CODE:-DEMO2026}"
ACCOUNT="${DEMO_ACCOUNT:-demo_admin}"
PASSWORD="${DEMO_PASSWORD:-123456}"
SKILL_ID="d3000000-0000-0000-0000-000000000001"
SPACE_ID="d2000000-0000-0000-0000-000000000001"
GAP_ID="aaaaaaaa-0000-0000-0000-000000000001"

fail() { echo "❌ $1"; exit 1; }
ok()   { echo "✅ $1"; }

TOKEN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d "{\"companyCode\":\"$COMPANY_CODE\",\"account\":\"$ACCOUNT\",\"password\":\"$PASSWORD\"}" \
  | jq -r '.data.token')
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || fail "登录失败"
ok "登录成功"

AUTH="Authorization: Bearer $TOKEN"
STAMP=$(date +%s)
# 足够长的随机后缀，避免与上一次回归文本被判为高重复
UNIQ=$(openssl rand -hex 8)

# ---------- M1/M2: 粘贴文本 ----------
TEXT1="我去年跟进一个客户，方案已经讲完，技术负责人也认可，但商务流程一直不启动。我没有直接找他们老板，而是先请这位负责人帮我列了一份内部评审清单，问清楚财务、法务、运维各自担心什么。然后我按清单逐条准备材料，由他转发给对应的人。三周后流程启动，年底前签下来。我的体会是，卡住的时候不要急着往上冲，先找到愿意帮你递话的人。编号${STAMP}${UNIQ}甲"
R1=$(curl -s -X POST "$BASE/admin/materials/text" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"text\":\"$TEXT1\",\"spaceId\":\"$SPACE_ID\",\"title\":\"回归-${STAMP}-A\"}")
S1=$(echo "$R1" | jq -r '.data.results[0].status')
C1=$(echo "$R1" | jq -r '.data.results[0].skillCreated')
[ "$S1" = "uploaded" ] || fail "M1 新文本应为 uploaded，实际 ${S1}（$(echo "$R1" | jq -r '.data.results[0].acceptance.rejectReason')）"
[ "$C1" = "false" ] || fail "M2 应复用已有分身，实际 skillCreated=$C1"
ok "M1/M2 新文本上传成功且复用已有分身"

R2=$(curl -s -X POST "$BASE/admin/materials/text" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"text\":\"$TEXT1\",\"spaceId\":\"$SPACE_ID\",\"title\":\"回归-${STAMP}-B\"}")
CODE2=$(echo "$R2" | jq -r '.data.results[0].acceptance.rejectCode')
[ "$CODE2" = "DUPLICATE" ] || fail "M1 重复文本应判 DUPLICATE，实际 $CODE2"
ok "M1 重复文本仍被正确拦截（判重未失效）"

# ---------- M3: text:// 进入清洗 ----------
MAT_ID=$(echo "$R1" | jq -r '.data.results[0].materialId')
ST=""
for i in $(seq 1 24); do
  ST=$(psql -d "$DB" -t -A -c "SELECT status FROM skill_material WHERE id='$MAT_ID'")
  case "$ST" in cleaning|analyzing|analyzed|extracted) break;; esac
  sleep 5
done
case "$ST" in
  cleaning|analyzing|analyzed|extracted) ok "M3 文本素材进入清洗（${ST}）";;
  *) fail "M3 文本素材仍停在 ${ST}";;
esac

# ---------- M4: 补录文字 ----------
printf 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=' \
  | base64 -d > /tmp/batch0-${STAMP}.png
UP=$(curl -s -X POST "$BASE/admin/materials/upload?skillId=$SKILL_ID" -H "$AUTH" \
  -F "files=@/tmp/batch0-${STAMP}.png")
IMG_ID=$(echo "$UP" | jq -r '.data.results[0].materialId')
NOTE=""
for i in $(seq 1 20); do
  NOTE=$(psql -d "$DB" -t -A -c "SELECT coalesce(analysis_notes,'') FROM skill_material WHERE id='$IMG_ID'")
  echo "$NOTE" | grep -q "需人工补充" && break
  sleep 5
done
echo "$NOTE" | grep -q "需人工补充" || fail "M4 图片未进入待补录状态（$NOTE）"
MANUAL="这张截图是我和客户采购的对话。我先确认他们今年的降本目标，再把报价拆成三年总成本，和现有方案的维护费用逐项对比。客户看到第三年的差距后，主动问能不能把付款节点往后放。我提出用阶段性验收来换付款节奏，最后双方各让一步签下来。编号${STAMP}乙"
curl -s -X PUT "$BASE/admin/materials/$IMG_ID/manual-text" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"text\":\"$MANUAL\"}" > /dev/null
ST2=$(psql -d "$DB" -t -A -c "SELECT status FROM skill_material WHERE id='$IMG_ID'")
[ "$ST2" = "parsed" ] || fail "M4 补录后应为 parsed，实际 $ST2"
ok "M4 补录文字进入清洗队列"

# ---------- K1/K2: 手动颗粒向量 ----------
GRAIN=$(curl -s -X POST "$BASE/admin/grains" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"skillId\":\"$SKILL_ID\",\"sceneTag\":\"回归场景${STAMP}\",\"sceneDescription\":\"客户要求把付款节点整体后移，但不愿接受额外条件\",\"expertThought\":\"先确认现金流压力来源，再用阶段验收交换付款节奏\",\"standardScript\":\"付款节点可以谈，但需要和阶段验收绑定\",\"applicableCondition\":\"客户认可价值但短期现金紧张\"}")
GID=$(echo "$GRAIN" | jq -r '.data.id')
HASVEC=$(psql -d "$DB" -t -A -c "SELECT embedding IS NOT NULL FROM experience_grain WHERE id='$GID'")
[ "$HASVEC" = "t" ] || fail "K1/K2 颗粒向量未写入"
ok "K1/K2 手动颗粒已完成向量化"

# ---------- K3: 补颗粒关闭缺口 ----------
psql -d "$DB" -q -c "INSERT INTO knowledge_gap (id, skill_id, space_id, query, scene_tag, attempted_query_count, status, created_at, updated_at) VALUES ('$GAP_ID','$SKILL_ID','$SPACE_ID','回归测试：无限期免费试用','免费试用',1,'open',now(),now()) ON CONFLICT (id) DO UPDATE SET status='open', resolved_at=NULL"
curl -s -X POST "$BASE/admin/grains" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"skillId\":\"$SKILL_ID\",\"gapId\":\"$GAP_ID\",\"sceneTag\":\"免费试用边界${STAMP}\",\"sceneDescription\":\"客户要求无限期免费试用且不承诺采购时间\",\"expertThought\":\"无限期试用等于没有决策节点\",\"standardScript\":\"试用可以，但先写下要验证的三件事\",\"applicableCondition\":\"客户以试用代替决策时\"}" > /dev/null
GAPST=$(psql -d "$DB" -t -A -c "SELECT status FROM knowledge_gap WHERE id='$GAP_ID'")
[ "$GAPST" = "resolved" ] || fail "K3 缺口未关闭，实际 $GAPST"
ok "K3 补颗粒后缺口自动关闭"

# ---------- A6: 非法状态被拒 ----------
BAD=$(curl -s -X PUT "$BASE/skills/$SKILL_ID/status" -H "$AUTH" -H 'Content-Type: application/json' -d '{"status":"failed"}')
[ "$(echo "$BAD" | jq -r '.code')" = "400" ] || fail "A6 非法状态未被拒绝"
ok "A6 非法分身状态被拒绝"

# ---------- R1: 自任教练标记 ----------
SELF=$(curl -s "$BASE/sales/reviews/d5000000-0000-0000-0000-000000000001/coach-supplement" -H "$AUTH" | jq -r '.data.selfCoach')
[ "$SELF" = "true" ] || fail "R1 selfCoach 应为 true，实际 $SELF"
ok "R1 自任教练标记正确"

rm -f /tmp/batch0-${STAMP}.png
echo
echo "批次 0 回归全部通过。"
