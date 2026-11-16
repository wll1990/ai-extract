#!/bin/bash
set -e

# Token 按天统计/T+1 窗口都按业务日（北京时间）计算；容器/服务器时区必须固定
export TZ=Asia/Shanghai

# 加载环境变量（secrets.env 由运维在服务器创建，不随仓库部署）
set -a
source /opt/mindforge/config/backend.env
if [ -f /opt/mindforge/config/secrets.env ]; then
  source /opt/mindforge/config/secrets.env
fi
set +a

cd /opt/mindforge/backend
pkill -f "backend.jar" 2>/dev/null || true

# 必须等旧进程真正退出：否则新进程因端口占用启动失败，
# 而健康检查会打到尚未退出的旧进程，误报“ready”（会继续跑旧 jar）
for _ in $(seq 1 30); do
  pgrep -f "backend.jar" >/dev/null 2>&1 || break
  sleep 1
done
if pgrep -f "backend.jar" >/dev/null 2>&1; then
  echo "旧进程未退出，强制结束"
  pkill -9 -f "backend.jar" 2>/dev/null || true
  sleep 1
fi

nohup java -Duser.timezone=Asia/Shanghai -Xms256m -Xmx512m -jar backend.jar > ../logs/backend.log 2>&1 &
echo $! > ../logs/backend.pid

echo "backend starting (pid $(cat ../logs/backend.pid))..."
# 冷启动实测约 150s，留足余量
bash /opt/mindforge/scripts/wait-ready.sh backend http://127.0.0.1:8080/api/v1/public/stats 240
