#!/bin/bash
# 一键全量部署 — 停服 → 构建上传 → 启服
# 用法: bash scripts/deploy-all.sh
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"

cd "$ROOT"

echo "========================================="
echo "  全量部署开始"
echo "========================================="

# 0. 先把启停脚本同步到服务器
echo ""
echo "📋 同步启停脚本到服务器..."
ssh mindforge "mkdir -p /opt/mindforge/scripts"
scp scripts/stop-all.sh \
    scripts/start-backend.sh \
    scripts/start-ai-service.sh \
    scripts/start-platform.sh \
    scripts/start-frontend.sh \
    mindforge:/opt/mindforge/scripts/
ssh mindforge "chmod +x /opt/mindforge/scripts/*.sh"

# 1. 停服
echo ""
echo "🛑 停止所有服务..."
ssh mindforge "bash /opt/mindforge/scripts/stop-all.sh 2>/dev/null || true"
sleep 2

# 2. 构建+上传
echo ""
echo "📦 1/3 后端..."
bash scripts/deploy-backend.sh

echo ""
echo "📦 2/3 平台端..."
bash scripts/deploy-platform.sh

echo ""
echo "📦 3/3 管理后台..."
bash scripts/deploy-frontend.sh

# 3. 启服
echo ""
echo "🚀 启动所有服务..."
ssh mindforge "bash /opt/mindforge/scripts/start-backend.sh"
ssh mindforge "bash /opt/mindforge/scripts/start-ai-service.sh"
ssh mindforge "bash /opt/mindforge/scripts/start-platform.sh"
ssh mindforge "bash /opt/mindforge/scripts/start-frontend.sh"

echo ""
echo "========================================="
echo "  ✅ 部署完成"
echo "========================================="
echo ""
echo "验证:"
echo "  curl http://47.116.138.205:8080/api/v1/public/stats"
echo "  curl http://47.116.138.205:3000"
echo "  curl http://47.116.138.205:3001"