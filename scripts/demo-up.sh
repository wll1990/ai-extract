#!/bin/bash
# ============================================================
# demo-up.sh — 重建演示企业域数据
#
# 用法（仓库根目录）：
#   bash scripts/demo-up.sh
#
# 行为：先按演示企业精确清理，再重新写入种子数据。
# 不触碰其它租户。可重复执行。
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

set -a
# shellcheck disable=SC1091
source "$ROOT/.env"
set +a

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_DATABASE="${PG_DATABASE:-aiextract}"

export PGPASSWORD="$PG_PASSWORD"
PSQL=(psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DATABASE" -v ON_ERROR_STOP=1)

echo "== 清理演示企业域 =="
"${PSQL[@]}" -q -f "$ROOT/scripts/demo-reset.sql"

echo "== 写入演示数据 =="
"${PSQL[@]}" -q -f "$ROOT/scripts/demo-seed.sql"

# demo-seed 只写文本，不写向量；不回填的话黄金集回归时 RAG 检索不到颗粒
echo "== 回填演示颗粒向量 =="
node "$ROOT/scripts/golden/backfill-demo-embeddings.mjs" || echo "（向量回填失败，可稍后单独重跑）"

cat <<EOF

演示数据已就绪。

  登录页：http://localhost:3000/login
  企业 ID：d0000000-0000-0000-0000-000000000001
  账号：demo_admin / demo_coach / demo_learner
  密码：123456

验证：bash scripts/demo-verify.sh
EOF
