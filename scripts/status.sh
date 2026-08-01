#!/bin/bash
# 查看所有服务状态 — 在服务器上执行
# 用法: ssh mindforge "bash /opt/mindforge/scripts/status.sh"
echo "服务          端口   PID     启动时间"
echo "──────────────────────────────────────────"

# backend
p=$(pgrep -f 'backend.jar' 2>/dev/null | head -1)
if [ -n "$p" ]; then
    t=$(ps -p $p -o lstart= 2>/dev/null | xargs)
    echo "backend       :8080  $p  $t"
else
    echo "backend       :8080  -       未运行"
fi

# ai-service
p=$(pgrep -f 'uvicorn' 2>/dev/null | head -1)
if [ -n "$p" ]; then
    t=$(ps -p $p -o lstart= 2>/dev/null | xargs)
    echo "ai-service    :8000  $p  $t"
else
    echo "ai-service    :8000  -       未运行"
fi

# frontend / platform — 用 ss 查端口映射到 pid
for port in 3000 3001; do
    case $port in 3000) name=frontend ;; 3001) name=platform ;; esac
    pid=$(ss -tlnp 2>/dev/null | grep ":$port " | grep -oP 'pid=\K[0-9]+' | head -1)
    if [ -n "$pid" ]; then
        t=$(ps -p $pid -o lstart= 2>/dev/null | xargs)
        printf '%-14s :%-5s %-7s %s\n' $name $port $pid "$t"
    else
        printf '%-14s :%-5s %-7s 未运行\n' $name $port -
    fi
done
