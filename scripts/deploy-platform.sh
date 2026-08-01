#!/bin/bash
# 平台端（C端）构建打包
# 用法: bash scripts/deploy-platform.sh
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"; cd "$ROOT"

echo "=== 平台端打包 ==="

[ ! -f config/platform.env ] && { echo "❌ config/platform.env 不存在"; exit 1; }
cp config/platform.env platform/.env.production

cd platform && npm run build

[ ! -f .next/standalone/server.js ] && { echo "❌ standalone 产物缺失"; exit 1; }

echo ""
echo "产物: .next/standalone/"
echo "上传开始"

ssh mindforge "mkdir -p /opt/mindforge/platform && rm -rf /opt/mindforge/platform/.next /opt/mindforge/platform/node_modules /opt/mindforge/platform/server.js /opt/mindforge/platform/package.json"
cd .next/standalone && scp -r * mindforge:/opt/mindforge/platform/
cd "$ROOT"

echo "上传服务器完成✅"
