#!/bin/bash
set -e

cd /opt/mindforge/platform
# 用端口杀旧进程（进程名是 next-server 不是 node server）
kill $(ss -tlnp 2>/dev/null | grep ':3001 ' | grep -oP 'pid=\K[0-9]+') 2>/dev/null || true
sleep 1

HOSTNAME=0.0.0.0 PORT=3001 nohup node server.js > ../logs/platform.log 2>&1 &
echo $! > ../logs/platform.pid

sleep 3
curl -s -o /dev/null -w "platform: HTTP %{http_code}\n" http://127.0.0.1:3001
echo "platform started (pid $(cat ../logs/platform.pid))"
