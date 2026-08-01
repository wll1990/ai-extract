#!/bin/bash
# 平台端（C端）构建打包
# 用法: bash scripts/deploy-platform.sh
# 产物: platform/.next/standalone/ (自包含，服务器无需 npm install)
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"

cd "$ROOT"

echo "=== 平台端打包 ==="

# 检查生产环境变量文件
if [ ! -f config/platform.env ]; then
    echo "❌ config/platform.env 不存在，请先创建"
    exit 1
fi
cp config/platform.env platform/.env.production

# 构建（output: standalone 自包含产物）
cd platform && npm run build

if [ ! -f .next/standalone/server.js ]; then
    echo "❌ standalone 产物缺失，请确认 next.config.js 有 output: 'standalone'"
    exit 1
fi

echo ""
echo "产物: .next/standalone/"
echo "上传开始"

# standalone 自包含——无需 package.json、无需 npm install
# public/ 静态文件也要上传
scp -r .next/standalone/ mindforge:/opt/mindforge/platform/standalone/
scp -r public/ mindforge:/opt/mindforge/platform/standalone/

echo "上传服务器完成✅"
echo "服务器端启动: cd /opt/mindforge/platform/standalone && node server.js"
