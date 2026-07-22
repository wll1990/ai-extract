#!/bin/bash
set -a && source "$(dirname "$0")/.env" && set +a
DIR="$(dirname "$0")"

echo "=== 停止旧服务 ==="
lsof -ti:8080 | xargs kill -9 2>/dev/null && echo "后端已停止" || echo "后端未运行"
lsof -ti:3000 | xargs kill -9 2>/dev/null && echo "管理后台已停止" || echo "管理后台未运行"
lsof -ti:3001 | xargs kill -9 2>/dev/null && echo "企业端已停止" || echo "企业端未运行"
lsof -ti:8000 | xargs kill -9 2>/dev/null && echo "文件解析已停止" || echo "文件解析未运行"

echo "=== 启动文件解析服务 (Python) :8000 ==="
cd "$DIR/ai-service" && nohup uvicorn main:app --host 0.0.0.0 --port 8000 > /tmp/file-parser.log 2>&1 &
sleep 2 && curl -s -o /dev/null -w "文件解析: HTTP %{http_code}\n" http://localhost:8000/health

echo "=== 启动后端 (dev) :8080 ==="
cd "$DIR/backend" && mvn clean spring-boot:run -q &
sleep 18 && curl -s -o /dev/null -w "后端: HTTP %{http_code}\n" -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d '{"account":"admin","password":"123456","companyId":"c0000000-0000-0000-0000-000000000001"}'

echo "=== 启动前端管理后台 (dev) :3000 ==="
cd "$DIR/frontend" && npm run dev &
sleep 5 && echo "管理后台: http://localhost:3000"

echo "=== 启动企业端 (dev) :3001 ==="
cd "$DIR/enterprise" && npm run dev -- -p 3001 &
sleep 5 && echo "企业端: http://localhost:3001"
