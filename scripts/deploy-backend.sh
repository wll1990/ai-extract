#!/bin/bash
# 后端构建打包
# 用法: bash scripts/deploy-backend.sh
# 产物: backend/target/ai-extract-backend-*.jar
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"

cd "$ROOT"

echo "=== 后端打包 ==="
cd backend && mvn clean package -DskipTests -q

JAR=$(ls target/*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
    echo "❌ 构建失败：target/ 下没有 jar 文件"
    exit 1
fi

echo ""
echo "产物: $JAR"
echo "上传开始"
scp "$JAR" mindforge:/opt/mindforge/backend/
echo "上传服务器完成✅"
