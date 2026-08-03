最终脚本全景
一键部署

bash scripts/deploy-all.sh

[0/6] 同步启停脚本到服务器
[1/6] 停止所有服务
[2/6] 构建+上传后端     → mvn package → scp jar → /opt/mindforge/backend/
[3/6] 构建+上传平台端    → next build → scp standalone/* → /opt/mindforge/platform/
[4/6] 构建+上传管理后台  → next build → scp standalone/* → /opt/mindforge/frontend/
[5/6] 同步生产配置       → scp config/*.env → /opt/mindforge/config/
[6/6] 启动所有服务
服务器目录结构

/opt/mindforge/
├── backend/
│   └── backend.jar                    ← scp 上传
├── platform/
│   └──                  ← scp 上传（自包含）
│       ├── server.js
│       ├── node_modules/
│       └── public/
├── frontend/
│   └──                     ← scp 上传（自包含）
│       ├── server.js
│       ├── node_modules/
│       └── public/
├── ai-service/                        ← 独立部署（Python）
├── config/                            ← scp config/*.env
├── scripts/                           ← scp start/stop 脚本
└── logs/                              ← 所有日志 + PID
启停
脚本	位置
stop-all.sh	PID 停 → 等 2s → pkill 兜底
start-backend.sh	java -jar → :8080
start-ai-service.sh	uvicorn → :8000
start-platform.sh	node server.js → :3001
start-frontend.sh	node server.js → :3000
服务健康检查

curl http://47.116.138.205:8080/api/v1/public/stats   # backend
curl http://47.116.138.205:8000/health                  # ai-service
curl http://47.116.138.205:3001                         # platform
curl http://47.116.138.205:3000                         # frontend

ps -eo pid,etime,cmd | grep -E 'java.*backend|next-server|uvicorn' | grep -v grep

ps -eo pid,lstart,cmd | grep next-server | grep -v grep


# 先看端口有没有在监听
ss -tlnp | grep -E '3000|3001'

# 再看所有 node 进程
ps aux | grep node