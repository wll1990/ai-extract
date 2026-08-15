#!/bin/bash
# 平台端（C端）构建打包
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"; cd "$ROOT"

echo "=== 平台端打包 ==="

[ ! -f config/platform.env ] && { echo "❌ config/platform.env 不存在"; exit 1; }
cp config/platform.env platform/.env.production

cd platform

# 1. 干净构建：清掉旧 .next，杜绝旧 HTML/静态资源哈希与新构建错位
rm -rf .next
npm run build

[ ! -f .next/standalone/server.js ] && { echo "❌ standalone 产物缺失"; exit 1; }

# 2. 把 static/ 与 public/ 打进 standalone，变成自包含产物
cp -R .next/static .next/standalone/.next/static
[ -d public ] && cp -R public .next/standalone/public

echo "产物: .next/standalone/"
echo "上传开始"

# 3. 单次 --delete 同步：HTML 与 CSS/JS 哈希永远来自同一次构建，且清掉过期文件
rsync -av --delete .next/standalone/ mindforge:/opt/mindforge/platform/

echo "[platform]上传服务器完成✅"
