#!/bin/bash
# 管理后台（B端）构建打包
# 用法: bash scripts/deploy-frontend.sh
# 产物: frontend/.next/standalone/ (自包含，服务器无需 npm install)
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"

cd "$ROOT"

echo "=== 管理后台打包 ==="

if [ ! -f config/frontend.env ]; then
    echo "❌ config/frontend.env 不存在，请先创建"
    exit 1
fi
cp config/frontend.env frontend/.env.production

cd frontend && npm run build

if [ ! -f .next/standalone/server.js ]; then
    echo "❌ standalone 产物缺失，请确认 next.config.js 有 output: 'standalone'"
    exit 1
fi

echo ""
echo "产物: .next/standalone/"
echo "上传开始"

# 清旧 standalone，上传新的
ssh mindforge "mkdir -p /opt/mindforge/frontend/standalone && rm -rf /opt/mindforge/frontend/standalone/*"
scp -r .next/standalone/* mindforge:/opt/mindforge/frontend/standalone/
scp -r public/ mindforge:/opt/mindforge/frontend/standalone/

echo "上传服务器完成✅"
