#!/bin/bash
# 启动所有服务 — 逐服务启动，单个失败不中断，最后汇总退出码
# 用法: ssh mindforge "bash /opt/mindforge/scripts/start-all.sh"

fail=0

echo "=== 启动后端 :8080 ==="
bash /opt/mindforge/scripts/start-backend.sh || { echo "⚠️ 后端启动异常（logs/backend.log）"; fail=1; }

echo "=== 启动 AI 文件解析服务 :8000 ==="
bash /opt/mindforge/scripts/start-ai-service.sh || { echo "⚠️ AI 服务启动异常（logs/ai-service.log）"; fail=1; }

echo "=== 启动平台端 :3001 ==="
bash /opt/mindforge/scripts/start-platform.sh || { echo "⚠️ 平台端启动异常（logs/platform.log）"; fail=1; }

echo "=== 启动管理后台 :3000 ==="
bash /opt/mindforge/scripts/start-frontend.sh || { echo "⚠️ 管理后台启动异常（logs/frontend.log）"; fail=1; }

echo "=== 启动 IM 桥 :3010 ==="
bash /opt/mindforge/scripts/start-im-bridge.sh || { echo "⚠️ IM 桥启动异常（logs/im-bridge.log）"; fail=1; }

echo ""
if [ "$fail" -eq 0 ]; then
  echo "✅ 全部服务启动完成"
else
  echo "❌ 部分服务启动失败，见上方 ⚠️ 与对应日志"
fi

echo ""
bash /opt/mindforge/scripts/status.sh

exit "$fail"
