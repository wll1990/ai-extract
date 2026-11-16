#!/bin/bash
# IM 桥部署 — 本地打包（esbuild）→ 上传单文件产物（服务器无需 npm install）
# 承载飞书 / 企业微信 / 钉钉三种渠道
# 用法: bash scripts/deploy-im-bridge.sh
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"
cd "$ROOT"

echo "=== IM 桥部署 ==="

# 1. 本地打包成单文件自包含产物（依赖内联；CJS 输出绕开 ESM 动态 require 限制）
echo "[1/2] 本地打包（esbuild）..."
cd im-bridge
[ -d node_modules ] || npm install
npm run build
[ ! -f dist/index.cjs ] && { echo "❌ 构建失败：dist/index.cjs 不存在"; exit 1; }

# 2. 上传产物（服务器不传源码、不装 node_modules）
echo "[2/2] 上传产物到服务器..."
ssh mindforge "mkdir -p /opt/mindforge/im-bridge/dist"
rsync -av dist/index.cjs mindforge:/opt/mindforge/im-bridge/dist/

echo "[im-bridge]部署完成✅"
