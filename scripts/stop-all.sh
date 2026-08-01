#!/bin/bash
# 停止所有服务 — PID 文件 + pkill 兜底
set -e

cd /opt/mindforge/logs 2>/dev/null || { echo "logs dir missing, nothing to stop"; exit 0; }

# 1. 按 PID 文件优雅停
for f in *.pid; do
    [ -f "$f" ] || continue
    pid=$(cat "$f" 2>/dev/null)
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null && echo "stopped $f (pid $pid)" || true
    fi
    rm -f "$f"
done

# 2. 等待进程退出
sleep 2

# 3. pkill 兜底 — 强制杀残留
pkill -f "backend.jar" 2>/dev/null || true
pkill -f "PORT=3000 node server.js" 2>/dev/null || true
pkill -f "PORT=3001 node server.js" 2>/dev/null || true
pkill -f "uvicorn main:app" 2>/dev/null || true

echo "all services stopped"
