#!/bin/bash
# 一键全量部署 — 停服 → 构建上传 → 启服
# 用法: bash scripts/deploy-all.sh
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"

cd "$ROOT"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BLUE}=========================================${NC}"
echo -e "${BLUE}  全量部署开始${NC}"
echo -e "${BLUE}=========================================${NC}"

# 0. 同步启停脚本
echo -e "\n${YELLOW}[0/8] 同步启停脚本到服务器...${NC}"
ssh mindforge "mkdir -p /opt/mindforge/scripts"
scp -q scripts/stop-all.sh \
    scripts/wait-ready.sh \
    scripts/start-all.sh \
    scripts/start-backend.sh \
    scripts/start-ai-service.sh \
    scripts/start-platform.sh \
    scripts/start-frontend.sh \
    scripts/start-im-bridge.sh \
    scripts/status.sh \
    mindforge:/opt/mindforge/scripts/
ssh mindforge "chmod +x /opt/mindforge/scripts/*.sh"

# 0.5 质量门禁（可选）：DEPLOY_GATE=1 时先跑静态检查+单测+只读权限矩阵
if [ "${DEPLOY_GATE:-0}" = "1" ]; then
  echo -e "\n${YELLOW}[gate] 构建前质量门禁（qa:quick）...${NC}"
  npm run qa:quick
  echo -e "${GREEN}  gate 通过${NC}"
fi

# 1. 停服
echo -e "\n${YELLOW}[1/8] 停止所有服务...${NC}"
ssh mindforge "bash /opt/mindforge/scripts/stop-all.sh 2>/dev/null || true"
sleep 2

# 2-6. 构建+上传
echo -e "\n${YELLOW}[2/8] 构建+上传后端...${NC}"
bash scripts/deploy-backend.sh

echo -e "\n${YELLOW}[3/8] 构建+上传平台端...${NC}"
bash scripts/deploy-platform.sh

echo -e "\n${YELLOW}[4/8] 构建+上传管理后台...${NC}"
bash scripts/deploy-frontend.sh

echo -e "\n${YELLOW}[5/8] 部署 AI 服务...${NC}"
bash scripts/deploy-ai-service.sh

echo -e "\n${YELLOW}[6/8] 部署 IM 桥（飞书/企微/钉钉）...${NC}"
bash scripts/deploy-im-bridge.sh

# 7. 同步配置
echo -e "\n${YELLOW}[7/8] 同步生产配置...${NC}"
ssh mindforge "mkdir -p /opt/mindforge/config"
scp -q config/backend.env config/frontend.env config/platform.env mindforge:/opt/mindforge/config/ 2>/dev/null || true

# 8. 启服
echo -e "\n${YELLOW}[8/8] 启动所有服务...${NC}"
ssh mindforge "bash /opt/mindforge/scripts/start-all.sh"

# 8.5 发布后回归（可选）：POST_DEPLOY_QA=1 时对新版本跑 release gate
if [ "${POST_DEPLOY_QA:-0}" = "1" ]; then
  echo -e "\n${YELLOW}[post] 发布后回归（qa:release）...${NC}"
  if ! bash scripts/qa/release-gate.sh; then
    echo -e "${YELLOW}  ⚠ 回归失败：服务已上线，请立即查看 reports/qa 并决定回滚${NC}"
    exit 1
  fi
fi

echo -e "\n${GREEN}=========================================${NC}"
echo -e "${GREEN}  ✅ 部署完成${NC}"
echo -e "${GREEN}=========================================${NC}"
echo ""
echo "验证:"
echo "  curl http://47.116.138.205:8080/api/v1/public/stats"
echo "  curl http://47.116.138.205:3000"
echo "  curl http://47.116.138.205:3001"
echo "  curl http://47.116.138.205:3010/health"
