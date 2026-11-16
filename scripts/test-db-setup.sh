#!/bin/bash
# ============================================================
# test-db-setup.sh — 为培训 DB 集成测试准备独立 PostgreSQL 库
#
# 用法（仓库根目录）：
#   bash scripts/test-db-setup.sh
#   然后按输出的命令跑测试：
#   TEST_PG_URL=... TEST_PG_USER=... TEST_PG_PASSWORD=... mvn -f backend/pom.xml -Dtest=TrainingSchemaDbTest test
#
# 说明：
# - 默认库名 aiextract_test（可用 TEST_DB_NAME 覆盖），只建库不删库。
# - 连接参数取自 .env 的 PG_HOST/PG_PORT/PG_USER/PG_PASSWORD/PG_DATABASE。
# - 密码不会回显；脚本只打印连接 URL 和用户名。
# - 可重复执行：schema 脚本本身幂等。
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
set -a
# shellcheck disable=SC1091
source "$ROOT/.env"
set +a

TEST_DB="${TEST_DB_NAME:-aiextract_test}"
PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"

export PGPASSWORD="$PG_PASSWORD"
PSQL=(psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -v ON_ERROR_STOP=1)

echo "== 准备测试库 $TEST_DB @ $PG_HOST:$PG_PORT =="
if [[ "$("${PSQL[@]}" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$TEST_DB'")" != "1" ]]; then
  "${PSQL[@]}" -d postgres -c "CREATE DATABASE $TEST_DB"
  echo "已创建数据库 $TEST_DB"
else
  echo "数据库已存在，直接复用"
fi

echo "== 准备最小基础表（sales-schema 会 ALTER token_usage_log） =="
"${PSQL[@]}" -d "$TEST_DB" -q -c "
CREATE TABLE IF NOT EXISTS public.token_usage_log (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid,
    usage_date date NOT NULL,
    model_type character varying(20) NOT NULL,
    model_name character varying(100),
    input_tokens integer DEFAULT 0,
    output_tokens integer DEFAULT 0,
    created_at timestamp without time zone DEFAULT now(),
    prompt_chars integer DEFAULT 0,
    completion_chars integer DEFAULT 0
);" >/dev/null

echo "== 执行 training-schema.sql + sales-schema.sql（两次，验证幂等） =="
for i in 1 2; do
  "${PSQL[@]}" -d "$TEST_DB" -q -f "$ROOT/scripts/training-schema.sql" >/dev/null
  "${PSQL[@]}" -d "$TEST_DB" -q -f "$ROOT/scripts/sales-schema.sql" >/dev/null
  echo "  第 $i 次完成"
done

cat <<EOF

测试库已就绪。运行集成测试：

  TEST_PG_URL="jdbc:postgresql://$PG_HOST:$PG_PORT/$TEST_DB" \\
  TEST_PG_USER="$PG_USER" \\
  TEST_PG_PASSWORD="\$PG_PASSWORD" \\
  mvn -f backend/pom.xml -Dtest=TrainingSchemaDbTest,SalesSchemaDbTest test

（密码请从 .env 取，不要写进命令历史以外的文件。）
EOF
