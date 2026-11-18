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

# npm workspace 会把 react/react-dom/scheduler 提升到仓库根 node_modules，
# Next standalone 在 workspace 下不会自动带上；缺了会在 SSR 阶段报
# Cannot find module 'react/jsx-runtime'（静态 chunk 全部 500）。
for pkg in react react-dom scheduler; do
  [ -d "../node_modules/$pkg" ] && cp -R "../node_modules/$pkg" .next/standalone/node_modules/
done

echo "产物: .next/standalone/"
echo "上传开始"

# 3. 单次 --delete 同步：HTML 与 CSS/JS 哈希永远来自同一次构建，且清掉过期文件
rsync -av --delete .next/standalone/ mindforge:/opt/mindforge/platform/

# 4. 必须重启：运行中的旧进程持有旧 build，且静态文件已被 --delete 替换
scp "$ROOT/scripts/start-platform.sh" mindforge:/opt/mindforge/scripts/start-platform.sh
ssh mindforge "chmod +x /opt/mindforge/scripts/start-platform.sh && bash /opt/mindforge/scripts/start-platform.sh"

echo "[platform]上传服务器完成✅（已重启，静态资源与运行进程一致）"
