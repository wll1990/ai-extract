#!/bin/bash
# 管理后台（B端）构建打包
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"; cd "$ROOT"

echo "=== 管理后台打包 ==="

[ ! -f config/frontend.env ] && { echo "❌ config/frontend.env 不存在"; exit 1; }
cp config/frontend.env frontend/.env.production

cd frontend && npm run build

[ ! -f .next/standalone/server.js ] && { echo "❌ standalone 产物缺失"; exit 1; }

echo "产物: .next/standalone/"
echo "上传开始"

# 1. standalone 服务端包
rsync -av .next/standalone/ mindforge:/opt/mindforge/frontend/
# 2. static/ — standalone 不含，需单独同步
rsync -av .next/static/ mindforge:/opt/mindforge/frontend/.next/static/

echo "上传服务器完成✅"
