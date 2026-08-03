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
echo -e "\n${YELLOW}[0/6] 同步启停脚本到服务器...${NC}"
ssh mindforge "mkdir -p /opt/mindforge/scripts"
scp -q scripts/stop-all.sh \
    scripts/start-backend.sh \
    scripts/start-ai-service.sh \
    scripts/start-platform.sh \
    scripts/start-frontend.sh \
    mindforge:/opt/mindforge/scripts/
ssh mindforge "chmod +x /opt/mindforge/scripts/*.sh"

# 1. 停服
echo -e "\n${YELLOW}[1/6] 停止所有服务...${NC}"
ssh mindforge "bash /opt/mindforge/scripts/stop-all.sh 2>/dev/null || true"
sleep 2

# 2-4. 构建+上传
echo -e "\n${YELLOW}[2/6] 构建+上传后端...${NC}"
bash scripts/deploy-backend.sh

echo -e "\n${YELLOW}[3/6] 构建+上传平台端...${NC}"
bash scripts/deploy-platform.sh

echo -e "\n${YELLOW}[4/7] 构建+上传管理后台...${NC}"
bash scripts/deploy-frontend.sh

echo -e "\n${YELLOW}[5/7] 部署 AI 服务...${NC}"
bash scripts/deploy-ai-service.sh

# 6. 同步配置
echo -e "\n${YELLOW}[6/7] 同步生产配置...${NC}"
ssh mindforge "mkdir -p /opt/mindforge/config"
scp -q config/backend.env config/frontend.env config/platform.env mindforge:/opt/mindforge/config/ 2>/dev/null || true

# 7. 启服
echo -e "\n${YELLOW}[7/7] 启动所有服务...${NC}"
ssh mindforge "bash /opt/mindforge/scripts/start-backend.sh"
ssh mindforge "bash /opt/mindforge/scripts/start-ai-service.sh"
ssh mindforge "bash /opt/mindforge/scripts/start-platform.sh"
ssh mindforge "bash /opt/mindforge/scripts/start-frontend.sh"

echo -e "\n${GREEN}=========================================${NC}"
echo -e "${GREEN}  ✅ 部署完成${NC}"
echo -e "${GREEN}=========================================${NC}"
echo ""
echo "验证:"
echo "  curl http://47.116.138.205:8080/api/v1/public/stats"
echo "  curl http://47.116.138.205:3000"
echo "  curl http://47.116.138.205:3001"
