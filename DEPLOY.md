# MindForge 部署上线 · 执行手册

> 本地构建 → 上传生产服务器 → 远端重启。
> 整理于 2026-11-18，**新开窗口直接照这份执行**。
> `docs/上线部署手册.md`（2026-07-30 版）留作背景资料，里面的版本号/端口描述可能已过时，以本文为准。
> 多服务独立部署（ai-service / im-bridge 拆分、多实例、域名与网关、K8s）见 [docs/78-多服务部署与调用.md](docs/78-多服务部署与调用.md)。

---

## 0. 一句话

**本地打包 → `scp`/`rsync` 到 `47.116.138.205` → 远端 `start-*.sh` 重启。没有 Docker、没有 CI。**

---

## 1. 服务器与环境

| 项 | 值 |
|---|---|
| SSH 别名 | `mindforge` → `47.116.138.205`（root，`~/.ssh/id_rsa`） |
| 部署根目录 | `/opt/mindforge/` |
| 远端脚本 | `/opt/mindforge/scripts/{start,stop,status,wait-ready}.sh` |
| 远端日志 | `/opt/mindforge/logs/*.log` |
| 远端配置 | `/opt/mindforge/config/{backend,frontend,platform}.env` + `secrets.env`（运维创建，**不入库**） |

| 服务 | 端口 | 进程 | 启动脚本 |
|---|---|---|---|
| backend（Spring Boot） | 8080 | `backend.jar` | `start-backend.sh` |
| frontend（管理后台 Next） | 3000 | `next start -p 3000` | `start-frontend.sh` |
| platform（平台端 Next） | 3001 | `next start -p 3001` | `start-platform.sh` |
| ai-service（FastAPI） | 8000 | `uvicorn main:app` | `start-ai-service.sh` |
| im-bridge（飞书/企微/钉钉） | 3010 | `node index.js` | `start-im-bridge.sh` |

连通性自检（只读，不改任何东西）：

```bash
ssh -o BatchMode=yes -o ConnectTimeout=8 mindforge 'hostname; uptime'
```

---

## 2. 三条主命令

```bash
cd /Users/wanglei/Documents/opc-project/ai-extract

# ① 全量：同步脚本 → 停服 → 四个服务构建上传 → 同步配置 → 启服
bash scripts/deploy-all.sh

# ② 单服务（只更一个）
bash scripts/deploy-backend.sh      # mvn clean package → scp jar
bash scripts/deploy-frontend.sh     # 管理后台
bash scripts/deploy-platform.sh     # 平台端
bash scripts/deploy-ai-service.sh   # Python 文件解析
bash scripts/deploy-im-bridge.sh    # IM 桥

# ③ 只重启不重传（配置改了 / 进程挂了）
ssh mindforge 'bash /opt/mindforge/scripts/start-backend.sh'
```

可选开关：

```bash
DEPLOY_GATE=1   bash scripts/deploy-all.sh   # 构建前跑 npm run qa:quick
POST_DEPLOY_QA=1 bash scripts/deploy-all.sh  # 发布后跑 qa:release 回归
```

---

## 3. 一次标准发版（照抄）

### 3.1 发之前确认

```bash
git status --porcelain          # 看清楚这次要发什么改动
ls config/backend.env config/frontend.env config/platform.env   # 三个配置必须存在
```

**必须拿到用户的明确指令**（"发后端" / "全量发"）。脚本会真的停服，不允许自行发起。

### 3.2 备份（强烈建议，回滚靠它）

```bash
ssh mindforge 'cd /opt/mindforge && tar czf /opt/backup/mf-$(date +%Y%m%d-%H%M).tar.gz \
  backend/backend.jar frontend platform ai-service im-bridge 2>/dev/null || true'
```

### 3.3 执行

```bash
bash scripts/deploy-backend.sh     # 或 deploy-all.sh
```

### 3.4 验证

```bash
curl -s  http://47.116.138.205:8080/api/v1/public/stats ; echo
curl -s -o /dev/null -w "admin    %{http_code}\n" http://47.116.138.205:3000
curl -s -o /dev/null -w "platform %{http_code}\n" http://47.116.138.205:3001
curl -s  http://47.116.138.205:3010/health ; echo

ssh mindforge 'tail -40 /opt/mindforge/logs/backend.log'
ssh mindforge 'bash /opt/mindforge/scripts/status.sh'
```

后端冷启动实测约 **150 秒**，`start-backend.sh` 里的 `wait-ready.sh` 给了 240 秒余量——刚重启完立刻 curl 失败是正常的，等它 ready。

---

## 4. 每个脚本到底做了什么

**backend**（`deploy-backend.sh`）
`mvn clean package -DskipTests` → `scp target/backend.jar mindforge:/opt/mindforge/backend/`
远端 `start-backend.sh`：`export TZ=Asia/Shanghai` → 加载 `backend.env` + `secrets.env` → pkill 旧 jar **并等它真正退出**（不等会出现"健康检查打到旧进程"的假 ready）→ nohup 启动 → `wait-ready.sh` 轮询 240s。

**frontend / platform**（Next standalone）
`rm -rf .next` → `npm run build` → 把 `.next/static`、`public`、以及 workspace 提升到根目录的 `react/react-dom/scheduler` 一起塞进 `.next/standalone` → `rsync -av --delete` 覆盖 → **远端重启**。
两个坑已经在脚本里处理，别手删：
- 不重启 = 旧进程持有旧 HTML，却引用已被 `--delete` 清掉的旧静态资源 → 页面无样式
- 不带 react 三件套 = SSR 报 `Cannot find module 'react/jsx-runtime'`，静态 chunk 全 500

**ai-service**：`rsync` 源码 → 装依赖 → 重启 uvicorn。
**im-bridge**：`rsync` 源码 → `npm ci` → 重启 node。

---

## 5. 回滚

```bash
# 用 3.2 的包还原
ssh mindforge 'cd /opt/mindforge && tar xzf /opt/backup/mf-20261118-1015.tar.gz'
ssh mindforge 'bash /opt/mindforge/scripts/start-all.sh'
```

`rsync --delete` 和 `scp` 都会覆盖旧产物，**没有自动回滚**——发版前的 tar 是唯一保险。

---

## 6. 常见故障对照

| 现象 | 原因 | 处理 |
|---|---|---|
| 后端 curl 不通 / 503 | 冷启动没到位（约 150s） | 等 `wait-ready`，或 `tail -f logs/backend.log` |
| 页面没样式、样式错乱 | 只传了文件没重启 | 重跑 `start-frontend.sh` |
| SSR 500：`Cannot find module 'react/jsx-runtime'` | standalone 少了 workspace 提升的依赖 | 脚本已处理；别手动精简上传内容 |
| 后端起不来、端口被占 | 旧进程没退干净 | `stop-all.sh` 再启 |
| 统计口径错位（今日/昨日） | 时区没固定 | 启动脚本已 `TZ=Asia/Shanghai`，别去掉 |
| `Host key verification failed` | 换机/重装 known_hosts | `ssh-keyscan -H 47.116.138.205 >> ~/.ssh/known_hosts` |
| `scp: ... No such file` | 远端目录缺失 | `ssh mindforge 'mkdir -p /opt/mindforge/{backend,logs}'` |

---

## 7. 硬规矩

1. **生产部署必须用户显式授权**，脚本会真停服；不要把"看看能不能连"理解成"可以发"
2. 只要跑 `mvn`，一律带 `clean`
3. 本地重启后端**只能** `sh dev.sh`，不要自己 `kill` 后端进程
4. `PARTNER_AES_KEY`、`JWT_SECRET`、数据库口令只在 `/opt/mindforge/config/secrets.env`，**不进仓库**
5. 生产库不做大范围删除；只允许按 runId 做限定范围的 qa-cleanup
6. 跨企业数据不得串用，企业私有内容不对外开放——改任何查询前先确认 company scope
7. 部署验证不能只看"接口 200"：权限、边界、兼容、并发、UI 都要过一遍（见 `docs/66-上线部署清单.md`）

---

## 8. 相关文件

| 文件 | 用途 |
|---|---|
| `scripts/deploy-all.sh` | 全量部署主流程 |
| `scripts/deploy-{backend,frontend,platform,ai-service,im-bridge}.sh` | 单服务 |
| `scripts/start-*.sh` / `stop-all.sh` / `status.sh` / `wait-ready.sh` | 远端启停与健康检查（随 `deploy-all` 同步到服务器） |
| `config/{backend,frontend,platform}.env` | 非敏感配置，随部署同步 |
| `docs/上线部署手册.md` | 2026-07 版完整手册（背景、环境变量清单、数据库准备） |
| `docs/66-上线部署清单.md` | 上线验收清单 |
