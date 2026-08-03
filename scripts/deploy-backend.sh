#!/bin/bash
# 后端构建打包
# 用法: bash scripts/deploy-backend.sh
# 产物: backend/target/backend.jar
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"
cd "$ROOT"

echo "=== 后端打包 ==="
cd backend && mvn clean package -DskipTests -q

JAR="target/backend.jar"
[ ! -f "$JAR" ] && { echo "❌ 构建失败：$JAR 不存在"; exit 1; }

echo "产物: $JAR"
echo "上传开始"
scp "$JAR" mindforge:/opt/mindforge/backend/
echo "[backend]上传服务器完成✅"
