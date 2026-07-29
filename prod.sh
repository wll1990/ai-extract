#!/bin/bash
set -e
DIR="$(dirname "$0")"
mkdir -p "$DIR/logs"

echo "=== 停止旧服务 ==="
lsof -ti:8080 | xargs kill -9 2>/dev/null || true
lsof -ti:3000 | xargs kill -9 2>/dev/null || true
lsof -ti:3001 | xargs kill -9 2>/dev/null || true
lsof -ti:8000 | xargs kill -9 2>/dev/null || true
sleep 1

echo "=== 启动文件解析服务 :8000 ==="
cd "$DIR/ai-service" && nohup uvicorn main:app --host 0.0.0.0 --port 8000 > "$DIR/logs/file-parser.log" 2>&1 &
echo $! > "$DIR/.file-parser.pid"
sleep 2 && curl -s -o /dev/null -w "  文件解析: HTTP %{http_code}\n" http://localhost:8000/health

echo "=== 打包后端 ==="
cd "$DIR/backend" && mvn clean package -DskipTests -q
JAR=$(ls "$DIR/backend/target"/*.jar | head -1)
echo "   jar: $JAR"

echo "=== 启动后端 (prod) :8080 ==="
nohup java -Xms256m -Xmx512m -jar "$JAR" > "$DIR/logs/backend.log" 2>&1 &
echo $! > "$DIR/.backend.pid"
sleep 15
curl -s -o /dev/null -w "  后端: HTTP %{http_code}\n" -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d '{"account":"admin","password":"123456","companyCode":"DEFAULT01"}'

echo "=== 打包前端管理后台 ==="
cd "$DIR/frontend" && npm run build 2>&1 | tail -1

echo "=== 启动前端管理后台 (prod) :3000 ==="
nohup npx next start -p 3000 "$DIR/frontend" > "$DIR/logs/frontend.log" 2>&1 &
echo $! > "$DIR/.frontend.pid"

echo "=== 打包平台端 ==="
cd "$DIR/platform" && npm run build 2>&1 | tail -1

echo "=== 启动平台端 (prod) :3001 ==="
nohup npx next start -p 3001 "$DIR/platform" > "$DIR/logs/platform.log" 2>&1 &
echo $! > "$DIR/.platform.pid"

sleep 3 && echo "  管理后台: http://localhost:3000"
echo "  企业端: http://localhost:3001"

echo "=== 完成 ==="
echo "后端日志: $DIR/logs/backend.log"
echo "管理后台日志: $DIR/logs/frontend.log"
echo "平台端日志: $DIR/logs/platform.log"
echo "文件解析日志: $DIR/logs/file-parser.log"
echo "停止服务: kill \$(cat $DIR/.backend.pid) \$(cat $DIR/.frontend.pid) \$(cat $DIR/.platform.pid) \$(cat $DIR/.file-parser.pid)"
