#!/bin/bash
set -e

# 加载环境变量（IM_BRIDGE_TOKEN 共享密钥在 backend.env）
set -a
source /opt/mindforge/config/backend.env
set +a

cd /opt/mindforge/im-bridge

# 用端口杀旧进程（进程名是 node dist/index.cjs）
kill $(ss -tlnp 2>/dev/null | grep ':3010 ' | grep -oP 'pid=\K[0-9]+') 2>/dev/null || true
sleep 1

PORT=3010 BACKEND_URL=http://127.0.0.1:8080/api/v1 nohup node dist/index.cjs > ../logs/im-bridge.log 2>&1 &
echo $! > ../logs/im-bridge.pid

echo "im-bridge starting (pid $(cat ../logs/im-bridge.pid))..."
bash /opt/mindforge/scripts/wait-ready.sh im-bridge http://127.0.0.1:3010/health 30
