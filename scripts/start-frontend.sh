#!/bin/bash
set -e

cd /opt/mindforge/frontend
# 用端口杀旧进程（进程名是 next-server 不是 node server）
kill $(ss -tlnp 2>/dev/null | grep ':3000 ' | grep -oP 'pid=\K[0-9]+') 2>/dev/null || true
sleep 1

HOSTNAME=0.0.0.0 PORT=3000 nohup node server.js > ../logs/frontend.log 2>&1 &
echo $! > ../logs/frontend.pid

echo "frontend starting (pid $(cat ../logs/frontend.pid))..."
bash /opt/mindforge/scripts/wait-ready.sh frontend http://127.0.0.1:3000 30
