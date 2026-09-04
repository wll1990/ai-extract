#!/bin/bash
set -e

cd /opt/mindforge/platform
# 用端口杀旧进程（进程名是 next-server 不是 node server）
kill $(ss -tlnp 2>/dev/null | grep ':3001 ' | grep -oP 'pid=\K[0-9]+') 2>/dev/null || true
sleep 1

HOSTNAME=0.0.0.0 PORT=3001 nohup node server.js > ../logs/platform.log 2>&1 &
echo $! > ../logs/platform.pid

echo "platform starting (pid $(cat ../logs/platform.pid))..."
bash /opt/mindforge/scripts/wait-ready.sh platform http://127.0.0.1:3001 30
