#!/bin/bash
# 管理后台（B端）构建打包
# 用法: bash scripts/deploy-frontend.sh
# 产物: frontend/.next/standalone/ (自包含，服务器无需 npm install)
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"

cd "$ROOT"

echo "=== 管理后台打包 ==="

# 检查生产环境变量文件
if [ ! -f config/frontend.env ]; then
    echo "❌ config/frontend.env 不存在，请先创建"
    exit 1
fi
cp config/frontend.env frontend/.env.production

# 构建（output: standalone 自包含产物）
cd frontend && npm run build

if [ ! -f .next/standalone/server.js ]; then
    echo "❌ standalone 产物缺失，请确认 next.config.js 有 output: 'standalone'"
    exit 1
fi

echo ""
echo "产物: .next/standalone/"
echo "上传开始"

# standalone 自包含（含 public/）—— 服务器无需 npm install
# 先清旧文件避免残留，再上传
ssh mindforge "mkdir -p /opt/mindforge/frontend && rm -rf /opt/mindforge/frontend/.next /opt/mindforge/frontend/node_modules"
scp -r .next/standalone/ mindforge:/opt/mindforge/frontend/

echo "上传服务器完成✅"
echo "服务器端启动: cd /opt/mindforge/frontend && node server.js"
