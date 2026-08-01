#!/bin/bash
set -e

cd /opt/mindforge/ai-service
pkill -f "uvicorn main:app" 2>/dev/null || true
sleep 1

nohup uvicorn main:app --host 0.0.0.0 --port 8000 > ../logs/ai-service.log 2>&1 &
echo $! > ../logs/ai-service.pid

sleep 2
curl -s http://127.0.0.1:8000/health
echo ""
echo "ai-service started (pid $(cat ../logs/ai-service.pid))"
