#!/bin/bash
set -e

cd /opt/mindforge/frontend
pkill -f "node server.js" 2>/dev/null || true
sleep 1

# standalone 模式直接用 node server.js，端口通过 PORT 环境变量控制
PORT=3000 nohup node server.js > ../logs/frontend.log 2>&1 &
echo $! > ../logs/frontend.pid

sleep 3
curl -s -o /dev/null -w "frontend: HTTP %{http_code}\n" http://127.0.0.1:3000
echo "frontend started (pid $(cat ../logs/frontend.pid))"
