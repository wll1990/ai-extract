#!/bin/bash
set -e

# 加载环境变量
set -a
source /opt/mindforge/config/backend.env
set +a

cd /opt/mindforge/backend
pkill -f "backend.jar" 2>/dev/null || true
sleep 2

nohup java -Xms256m -Xmx512m -jar backend.jar > ../logs/backend.log 2>&1 &
echo $! > ../logs/backend.pid

echo "backend starting (pid $(cat ../logs/backend.pid))..."
bash /opt/mindforge/scripts/wait-ready.sh backend http://127.0.0.1:8080/api/v1/public/stats 90
