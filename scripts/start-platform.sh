#!/bin/bash
set -e

cd /opt/mindforge/platform/standalone
pkill -f "PORT=3001 node server.js" 2>/dev/null || true
sleep 1

PORT=3001 nohup node server.js > ../../logs/platform.log 2>&1 &
echo $! > ../../logs/platform.pid

sleep 3
curl -s -o /dev/null -w "platform: HTTP %{http_code}\n" http://127.0.0.1:3001
echo "platform started (pid $(cat ../../logs/platform.pid))"
