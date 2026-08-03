#!/bin/bash
# AI 服务部署 — 同步代码 → 安装依赖 → 重启
# 用法: bash scripts/deploy-ai-service.sh
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"
cd "$ROOT"

echo "=== AI 服务部署 ==="

# 1. 同步源码
echo "[1/3] 同步源码到服务器..."
ssh mindforge "mkdir -p /opt/mindforge/ai-service/routers /opt/mindforge/ai-service/services /opt/mindforge/ai-service/scripts"
rsync -av ai-service/main.py \
          ai-service/requirements.txt \
          ai-service/logger_config.py \
          mindforge:/opt/mindforge/ai-service/
rsync -av ai-service/routers/ mindforge:/opt/mindforge/ai-service/routers/
rsync -av ai-service/services/ mindforge:/opt/mindforge/ai-service/services/
rsync -av ai-service/scripts/ mindforge:/opt/mindforge/ai-service/scripts/ 2>/dev/null || true
echo "[ai-service]上传完成✅"
# 2. 安装 Python 依赖（仅 requirements.txt 变更时）
echo "[2/3] 检查 Python 依赖..."
ssh mindforge "cd /opt/mindforge/ai-service && ( ! [ -f .req_hash ] || md5sum requirements.txt | cut -d' ' -f1 | cmp -s - .req_hash || ( pip install -r requirements.txt -q && md5sum requirements.txt | cut -d' ' -f1 > .req_hash && echo '[pip] 依赖已更新' ) )"

# 3. 重启服务
echo "[3/3] 重启 AI 服务..."
ssh mindforge "bash /opt/mindforge/scripts/start-ai-service.sh"

echo "[ai-service]部署完成✅"
