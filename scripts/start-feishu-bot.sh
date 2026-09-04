#!/bin/bash
set -e

# 加载环境变量（IM_BRIDGE_TOKEN 共享密钥在 backend.env）
set -a
source /opt/mindforge/config/backend.env
set +a

cd /opt/mindforge/feishu-bot

# 用端口杀旧进程（进程名是 node dist/index.cjs）
kill $(ss -tlnp 2>/dev/null | grep ':3010 ' | grep -oP 'pid=\K[0-9]+') 2>/dev/null || true
sleep 1

PORT=3010 BACKEND_URL=http://127.0.0.1:8080/api/v1 nohup node dist/index.cjs > ../logs/feishu-bot.log 2>&1 &
echo $! > ../logs/feishu-bot.pid

echo "feishu-bot starting (pid $(cat ../logs/feishu-bot.pid))..."
bash /opt/mindforge/scripts/wait-ready.sh feishu-bot http://127.0.0.1:3010/health 30
