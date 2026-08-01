#!/bin/bash
set -e

# 加载环境变量
set -a
source /opt/mindforge/config/backend.env
set +a

cd /opt/mindforge/backend
pkill -f "ai-extract-backend" 2>/dev/null || true
sleep 2

nohup java -Xms256m -Xmx512m -jar *.jar > ../logs/backend.log 2>&1 &
echo $! > ../logs/backend.pid

echo "backend starting (pid $(cat ../logs/backend.pid))..."
sleep 20
curl -s http://127.0.0.1:8080/api/v1/public/stats
echo ""
echo "backend ready"
