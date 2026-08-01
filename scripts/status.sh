#!/bin/bash
# 查看所有服务状态
# 用法: bash scripts/status.sh
ssh mindforge "
echo '服务          端口   PID     启动时间'
echo '──────────────────────────────────────────'

# backend
p=\$(pgrep -f 'backend.jar' 2>/dev/null | head -1)
[ -n \"\$p\" ] && t=\$(ps -p \$p -o lstart= 2>/dev/null | xargs) && echo \"backend       :8080  \$p  \$t\" || echo 'backend       :8080  -       未运行'

# ai-service
p=\$(pgrep -f 'uvicorn' 2>/dev/null | head -1)
[ -n \"\$p\" ] && t=\$(ps -p \$p -o lstart= 2>/dev/null | xargs) && echo \"ai-service    :8000  \$p  \$t\" || echo 'ai-service    :8000  -       未运行'

# frontend / platform — 用 ss 查端口映射到 pid
ss -tlnp 2>/dev/null | grep -E '3000|3001' | while read line; do
  port=\$(echo \$line | grep -oP ':\K300[01]')
  pid=\$(echo \$line | grep -oP 'pid=\K[0-9]+')
  time=\$(ps -p \$pid -o lstart= 2>/dev/null | xargs)
  case \$port in
    3000) name=frontend ;;
    3001) name=platform ;;
  esac
  printf '%-14s :%-5s %-7s %s\n' \$name \$port \$pid \"\$time\"
done
"
