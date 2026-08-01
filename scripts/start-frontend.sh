#!/bin/bash
set -e

cd /opt/mindforge/frontend
pkill -f "PORT=3000 node server.js" 2>/dev/null || true
sleep 1

PORT=3000 nohup node server.js > ../logs/frontend.log 2>&1 &
echo $! > ../logs/frontend.pid

sleep 3
curl -s -o /dev/null -w "frontend: HTTP %{http_code}\n" http://127.0.0.1:3000
echo "frontend started (pid $(cat ../logs/frontend.pid))"
