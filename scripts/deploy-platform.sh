#!/bin/bash
# 平台端（C端）构建打包
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"; cd "$ROOT"

echo "=== 平台端打包 ==="

[ ! -f config/platform.env ] && { echo "❌ config/platform.env 不存在"; exit 1; }
cp config/platform.env platform/.env.production

cd platform && npm run build

[ ! -f .next/standalone/server.js ] && { echo "❌ standalone 产物缺失"; exit 1; }

echo "产物: .next/standalone/"
echo "上传开始"

# 1. standalone 服务端包
rsync -av .next/standalone/ mindforge:/opt/mindforge/platform/
# 2. static/ — standalone 不含，需单独同步
rsync -av .next/static/ mindforge:/opt/mindforge/platform/.next/static/

echo "上传服务器完成✅"
