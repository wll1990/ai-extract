#!/bin/bash
# 平台端（C端）构建打包
# 用法: bash scripts/deploy-platform.sh
# 产物: platform/.next/
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

cd platform && npm run build

# 准备服务器用的 package.json（去除 workspace 依赖，服务器端无 monorepo）
# 临时文件操作，不修改本地 package.json
PKG_SERVER="package-server.json"
cp package.json "$PKG_SERVER"
if grep -q '@aiextract/shared-ui' "$PKG_SERVER"; then
    if [ "$(uname)" = "Darwin" ]; then
        sed -i '' '/@aiextract\/shared-ui/d' "$PKG_SERVER"
    else
        sed -i '/@aiextract\/shared-ui/d' "$PKG_SERVER"
    fi
fi

echo ""
echo "产物: .next/"
echo "上传开始"
scp -r .next "$PKG_SERVER" root@47.116.138.205:/opt/mindforge/platform/
rm "$PKG_SERVER"
echo "上传服务器完成✅"
