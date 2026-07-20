# API接口详细文档

**版本**：V1\.0  

**日期**：2026年6月29日 | **最后更新**：2026-07-01（新增6端点+命名统一）

**Base URL**：`http://localhost:8080/api/v1`  

**认证方式**：JWT Token，Header `Authorization: Bearer {token}`  

**Token有效期**：24小时，过期需重新登录

> **2026-07-01 新增端点**：
> `GET /skills/list` · `PUT /skills/{id}/status` · `POST /reports/{id}/rate` · `POST /reports/{id}/checklist` · `PUT /admin/experts/documents/{id}` · `GET /skills/{id}/practice-scenes`



---



## 通用说明



### 通用响应格式



**成功**：

```JSON
{
  "code": 200,
  "message": "success",
  "data": { }
}
```



**分页**：

```JSON
{
  "code": 200,
  "data": {
    "content": [],
    "page": 1,
    "size": 20,
    "total": 100,
    "totalPages": 5
  }
}
```



**错误**：

```JSON
{
  "code": 400,
  "message": "参数错误",
  "data": null
}
```



### SSE流式响应



```Plain Text
data: {"type":"chunk","content":"文字内容"}
data: {"type":"phase_change","phase":"storytelling"}
data: {"type":"source","label":"来源：周铭·《透视》第一步"}
data: {"type":"done"}
```



|type|说明|
|---|---|
|`chunk`|文本片段，逐字推送|
|`phase_change`|阶段切换|
|`source`|来源标注|
|`error`|出错|
|`done`|流结束|



### 错误码



|错误码|说明|
|---|---|
|200|成功|
|400|参数错误|
|401|未登录或Token过期|
|403|无权限|
|404|资源不存在|
|500|服务器内部错误|
|10001|AI服务超时|
|10002|AI服务返回异常|
|10003|访谈会话已完成，不能再发消息|
|10004|文件解析失败|



### 角色权限矩阵

> UI 层呈现为管理员/用户两层。后端保留 `super_admin`（管理员）/ `space_owner`（有空间用户）/ `employee`（普通用户）三值。
> Spring Security 规则：`/admin/**` 路径仅 `super_admin` 可访问。

|接口模块|super_admin（管理员）|space_owner（用户·有空间）|employee（用户）|
|---|---|---|---|
|认证（/auth/\*）|✅|✅|✅|
|空间（GET）|✅|✅|✅|
|空间（POST/PUT）|✅|✅|❌|
|访谈（全部）|✅|✅（仅自己的空间）|❌|
|报告（GET）|✅|✅|✅|
|报告（PUT）|✅|✅（仅自己的报告）|❌|
|报告（评分/清单）|✅|✅|✅|
|分身广场（/skills/list）|✅|✅|✅|
|分身管理（/skills/{id}/status）|✅|❌|❌|
|分身（问答/对练）|✅|✅|✅|
|分身（反馈/场景/评价）|✅|✅|✅|
|工具（全部）|✅|✅|✅|
|管理（全部）|✅|❌|❌|
|IM配置（全部）|✅|❌|❌|
|专家经验库（全部）|✅|❌|❌|
|专家列表（GET /experts/available）|✅|✅|✅|



---



## 接口总览



|编号|模块|方法|路径|说明|
|---|---|---|---|---|
|1|认证|POST|/auth/login|登录|
|2|认证|POST|/auth/register|注册|
|3|认证|GET|/auth/me|获取当前用户|
|4|空间|GET|/spaces|空间列表|
|5|空间|GET|/spaces/\{id\}|空间详情|
|6|空间|POST|/spaces|创建空间|
|7|空间|PUT|/spaces/\{id\}|编辑空间|
|8|访谈|POST|/interviews|创建访谈会话|
|9|访谈|GET|/interviews/\{id\}|获取会话状态|
|10|访谈|POST|/interviews/\{id\}/chat|发送消息（SSE）|
|11|访谈|GET|/interviews/\{id\}/messages|历史消息列表|
|12|访谈|POST|/interviews/\{id\}/resume|中断恢复|
|13|访谈|POST|/interviews/\{id\}/restart|重新开始|
|14|访谈|POST|/interviews/\{id\}/pause|暂停访谈|
|15|访谈|GET|/interviews/active|活跃会话检测|
|16|报告|GET|/reports|报告列表|
|17|报告|GET|/reports/\{id\}|报告详情|
|18|报告|PUT|/reports/\{id\}|编辑报告|
|19|报告|GET|/reports/\{id\}/download|下载Word/PPT|
|20|分身|POST|/skills/\{skillId\}/chat|个人分身问答（SSE）|
|21|分身|POST|/skills/\{skillId\}/practice/start|开始对练|
|22|分身|POST|/skills/\{skillId\}/practice/respond|对练回应（SSE）|
|23|分身|POST|/skills/enterprise/chat|企业总调度综合分身（SSE）|
|24|分身|POST|/skills/\{skillId\}/feedback|提交回答反馈|
|25|工具|GET|/tools|资料库列表|
|26|工具|GET|/tools/\{id\}/download|下载资料|
|27|管理员|GET|/admin/spaces|所有空间管理|
|28|管理员|GET|/admin/scene\-coverage|场景覆盖数据|
|29|管理员|PUT|/admin/config|企业配置|
|30|管理员|POST|/admin/invite|生成萃取邀请链接|
|31|专家经验|GET|/experts/available|可用萃取师列表|
|32|专家经验|GET|/admin/experts|萃取师列表|
|33|专家经验|GET|/admin/experts/\{id\}|萃取师详情|
|34|专家经验|POST|/admin/experts/upload|上传萃取师材料|
|35|专家经验|POST|/admin/experts/\{id\}/extract|提取锦囊|
|36|专家经验|PUT|/admin/experts/\{id\}/grains/\{grainId\}|编辑锦囊|
|37|专家经验|DELETE|/admin/experts/\{id\}/grains/\{grainId\}|删除锦囊|
|38|专家经验|POST|/admin/experts/\{id\}/activate|激活Skill|
|39|专家经验|POST|/admin/experts/\{id\}/documents|追加文件|
|40|专家经验|DELETE|/admin/experts/\{id\}/documents/\{docId\}|删除文件|
|41|专家经验|PUT|/admin/experts/\{id\}/documents/\{docId\}|替换文件|
|42|专家经验|POST|/admin/experts/composite/regenerate|重新生成综合Skill|
|43|专家经验|GET|/admin/experts/composite|获取综合Skill详情|
|44|IM|POST|/im/\{channel\}/callback|接收IM消息回调|
|45|IM|GET|/im/channels|获取IM渠道列表|
|46|IM|POST|/im/channels|新增IM渠道|
|47|IM|PUT|/im/channels/\{id\}|编辑IM渠道|
|48|IM|DELETE|/im/channels/\{id\}|删除IM渠道|
|49|IM|POST|/im/channels/\{id\}/test|测试连接|
|50|分身|GET|/skills/list|分身广场列表（所有激活分身）|
|51|分身|PUT|/skills/\{id\}/status|分身公开/停用（管理员）|
|52|分身|GET|/skills/\{id\}/practice-scenes|对练场景列表|
|53|分身|POST|/skills/\{id\}/practice/evaluate|对练综合评价|
|54|报告|POST|/reports/\{id\}/rate|报告评分|
|55|报告|POST|/reports/\{id\}/checklist|同步检查清单|
|56|专家经验|PUT|/admin/experts/documents/\{docId\}|更新文档内容（人工处理图片/音频）|



---



## 1\. 认证模块



### 1\.1 POST /api/v1/auth/login



**说明**：企业账号登录，返回JWT Token。Token有效期24小时。



**请求体**：

```JSON
{
  "companyId": "string",
  "account": "string",
  "password": "string"
}
```



|参数|类型|必填|说明|
|---|---|---|---|
|companyId|string|是|企业ID|
|account|string|是|账号|
|password|string|是|密码|



**响应体**：

```JSON
{
  "code": 200,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIs...",
    "user": {
      "id": "uuid",
      "name": "周铭",
      "role": "employee",
      "avatarUrl": "https://..."
    }
  }
}
```



|字段|类型|说明|
|---|---|---|
|token|string|JWT Token|
|user\.id|string|用户唯一标识|
|user\.name|string|用户姓名|
|user\.role|string|角色：super\_admin / space\_owner / employee|
|user\.avatarUrl|string|头像URL|



**实现逻辑**：

1. 查询 `user` 表，匹配 `account` 和 `company_id`

2. 验证 `password_hash`

3. 检查 `is_active = true`

4. 生成JWT Token（payload包含 userId, companyId, role）

5. 返回Token和用户信息

6. 密码错误返回401；用户不存在返回404

    

---



### 1\.2 POST /api/v1/auth/register



**说明**：注册新用户。



**请求体**：

```JSON
{
  "companyId": "string",
  "name": "string",
  "account": "string",
  "password": "string",
  "role": "employee"
}
```



|参数|类型|必填|说明|
|---|---|---|---|
|companyId|string|是|企业ID|
|name|string|是|用户姓名|
|account|string|是|登录账号|
|password|string|是|密码|
|role|string|是|角色|



**响应体**：同登录



**实现逻辑**：

1. 校验 `account` 在企业内唯一

2. 密码BCrypt加密

3. 插入 `user` 表

4. 返回Token和用户信息

    

---



### 1\.3 GET /api/v1/auth/me



**说明**：获取当前登录用户信息。



**响应体**：

```JSON
{
  "code": 200,
  "data": {
    "id": "uuid",
    "name": "周铭",
    "role": "employee",
    "avatarUrl": "https://...",
    "companyId": "uuid",
    "companyName": "XX集团"
  }
}
```



**实现逻辑**：从JWT中提取userId → 查询 `user` 表 → 关联查询 `company` 表获取企业名称。



---



## 2\. 空间模块



### 2\.1 GET /api/v1/spaces



**说明**：获取当前用户可访问的空间列表，支持分页、关键词搜索和场景标签筛选。



**请求参数**：



|参数|类型|必填|说明|
|---|---|---|---|
|page|int|否|页码，默认1|
|size|int|否|每页条数，默认20|
|keyword|string|否|搜索空间名称或作者名|
|tag|string|否|场景标签筛选|



**响应体**：

```JSON
{
  "code": 200,
  "data": {
    "content": [
      {
        "id": "uuid",
        "ownerName": "周铭",
        "ownerAvatar": "https://...",
        "title": "周铭的空间",
        "description": "金融科技大客户销售 · 连续3年销冠",
        "tags": ["银行", "B2B", "破冰"],
        "oneliner": "不是在卖药，是在开方子",
        "reportCount": 3,
        "skillStatus": "published",
        "createdAt": "2026-06-28T10:00:00"
      }
    ],
    "page": 1,
    "size": 20,
    "total": 2,
    "totalPages": 1
  }
}
```



**实现逻辑**：

1. 从JWT获取 `companyId` 和 `role`

2. 超管：查询企业下所有公开空间

3. 普通用户：查询公开空间 \+ 自己拥有的空间

4. 支持关键词模糊搜索（`title` 和 `ownerName`）

5. 支持 `tag` 过滤（JSONB查询）

6. 关联查询 `report` 表统计报告数量

7. 关联查询 `skill` 表获取分身状态

    

---



### 2\.2 GET /api/v1/spaces/\{spaceId\}



**说明**：获取指定空间的完整详情。



**响应体**：

```JSON
{
  "code": 200,
  "data": {
    "id": "uuid",
    "ownerName": "周铭",
    "ownerAvatar": "https://...",
    "ownerTitle": "金融科技大客户销售",
    "ownerTags": ["入职6年", "连续3年销冠"],
    "oneliner": "不是在卖药，是在开方子",
    "title": "周铭的空间",
    "description": "...",
    "tags": ["银行", "B2B"],
    "isPublic": true,
    "reports": [...],
    "skillStatus": "published",
    "skillId": "uuid",
    "stats": { "reportCount": 3, "viewCount": 1256, "skillCallCount": 892 },
    "downloads": { "posters": 12, "cards": 8, "checklists": 5, "scripts": 6 }
  }
}
```



**实现逻辑**：

1. 查询 `space` 表 → 关联 `user` 表获取销冠信息

2. 校验权限（公开空间 or 自己所有 or 超管）

3. 查询 `report` 表获取报告列表

4. 查询 `skill` 表获取分身状态

5. 查询 `tool` 表统计各类资料数量

    

---



### 2\.3 POST /api/v1/spaces



**说明**：创建空间。



**请求体**：

```JSON
{
  "userId": "uuid",
  "title": "周铭的空间",
  "description": "专攻城商行和股份制银行",
  "tags": ["银行", "B2B"],
  "isPublic": true
}
```



**实现逻辑**：

1. 校验权限（超管可指定userId，普通销冠只能给自己创建）

2. 插入 `space` 表

3. 自动创建 `skill` 记录（`status = 'generating'`）

    

---



### 2\.4 PUT /api/v1/spaces/\{spaceId\}



**说明**：编辑空间信息。



**请求体**：

```JSON
{
  "title": "周铭的空间（已更新）",
  "description": "...",
  "tags": ["银行", "B2B"],
  "isPublic": true,
  "oneliner": "不是在卖药，是在开方子"
}
```



**实现逻辑**：校验权限 → 更新 `space` 表。



---



## 3\. 访谈模块



### 3\.1 POST /api/v1/interviews



**说明**：创建新的访谈会话。



**请求体**：

```JSON
{
  "spaceId": "uuid",
  "topic": "搞定说太贵了的客户",
  "inviteCode": "abc123",
  "expertSkillId": "expert-uuid"
}
```



|参数|类型|必填|说明|
|---|---|---|---|
|spaceId|string|是|所属空间ID|
|topic|string|是|萃取主题|
|inviteCode|string|否|邀请码|
|expertSkillId|string|否|萃取师Skill ID。不传=综合Skill；"none"=基础版|



**响应体**：

```JSON
{
  "code": 200,
  "data": {
    "sessionId": "uuid",
    "topic": "搞定说太贵了的客户",
    "status": "created",
    "currentPhase": "opening",
    "expertSkillUsed": "综合",
    "phases": [
      {"name": "opening", "label": "开场定调", "status": "current"},
      {"name": "storytelling", "label": "故事深描", "status": "pending"},
      {"name": "modeling", "label": "模型提炼", "status": "pending"},
      {"name": "closing", "label": "收网确认", "status": "pending"}
    ],
    "templatePreview": {
      "modules": [
        {"name": "案例故事", "collected": false},
        {"name": "核心步骤模型", "collected": false},
        {"name": "关键决策点", "collected": false},
        {"name": "专家心法", "collected": false},
        {"name": "适用边界", "collected": false},
        {"name": "行动检查清单", "collected": false}
      ]
    }
  }
}
```



**实现逻辑**：

1. 从JWT提取 `userId`，校验 `spaceId` 属于当前用户

2. 解析 `expertSkillId`：

    - 不传 → `expertSkillUsed = "综合"`，加载 `expert_composite.md`

    - 传 `"none"` → `expertSkillUsed = "无"`，不加载萃取师经验

    - 传具体ID → 查询 `expert_skill` 表验证存在 → `expertSkillUsed = 萃取师名称`

3. 插入 `interview_session` 表（`status='created'`, `current_phase='opening'`）

4. 生成开场引导消息：

    - 判断该 `space_id` 是否有已完成访谈 → 选择完整版或简化版引导

    - 根据 `expertSkillId` 选择引导模板（综合/指定萃取师/基础版）

5. 将引导消息存入 `interview_message` 表（`role='ai'`, `depth=-1`）

6. 返回会话信息

    

---



### 3\.2 GET /api/v1/interviews/\{sessionId\}



**说明**：获取会话状态和进度。



**响应体**：

```JSON
{
  "code": 200,
  "data": {
    "sessionId": "uuid",
    "topic": "搞定说太贵了的客户",
    "status": "in_progress",
    "currentPhase": "storytelling",
    "phases": [...],
    "collectStatus": {
      "案例故事": "done",
      "核心步骤模型": "collecting",
      "关键决策点": "pending",
      "专家心法": "pending",
      "适用边界": "pending",
      "行动检查清单": "pending"
    }
  }
}
```



**实现逻辑**：

1. 查询 `interview_session` 表

2. 校验权限（会话所属空间的所有者 or 超管）

3. 映射 `collect_*` 字段为中文状态

    

---



### 3\.3 POST /api/v1/interviews/\{sessionId\}/chat（SSE）



**说明**：发送消息，AI流式返回追问。核心SSE接口。



**请求体**：

```JSON
{
  "message": "有个客户叫李总..."
}
```



**实现逻辑**：

1. 校验 `sessionId` 存在，`status` 为 `created` 或 `in_progress`

2. 如果 `status = 'created'`，更新为 `in_progress`

3. 用户消息存入 `interview_message` 表

4. 构建对话上下文：

a\. 加载 `interview_system.md`（基础System Prompt）

b\. 如果 `expert_skill_id` 不为空且不为"none"：

- 调用Python `expert_loader.py` 加载对应MD文件

- 替换 `{expert_tacit_knowledge}` 占位符

c\. 从 `interview_message` 表加载最近20条历史

d\. 读取 `current_phase` 和 `collect_*` 状态

5. 调用Python AI服务：

    - POST 内部REST接口

    - 传入完整上下文

    - 请求流式返回（`stream=True`）

6. 将AI返回的文本块封装为SSE事件推送给前端

7. 监听阶段切换事件 → 更新 `current_phase`

8. 监听采集标记 → 更新 `collect_*` 字段

9. AI消息存入 `interview_message` 表

10. 如果四阶段完成 → 更新 `status='completed'` → 触发异步报告生成

    

---



### 3\.4 GET /api/v1/interviews/\{sessionId\}/messages



**说明**：获取历史消息列表。



**响应体**：

```JSON
{
  "code": 200,
  "data": [
    {
      "id": "uuid",
      "role": "ai",
      "content": "请回忆过去半年里...",
      "depth": 0,
      "phase": "opening",
      "createdAt": "2026-06-28T10:00:00"
    }
  ]
}
```



**实现逻辑**：查询 `interview_message` 表，按 `created_at` 升序排列。



---



### 3\.5 POST /api/v1/interviews/\{sessionId\}/resume



**说明**：中断恢复。



**请求体**：

```JSON
{ "action": "resume" }
```



**实现逻辑**：

1. 校验 `status = 'in_progress'` 或 `'paused'`

2. 加载最近20条历史消息

3. 构建恢复提示词（含 `current_phase`、`collect_*` 状态、最近对话摘要）

4. 作为 `role='system'` 消息注入

5. 调用大模型 → SSE流式返回衔接消息

    

---



### 3\.6 POST /api/v1/interviews/\{sessionId\}/restart



**说明**：重新开始。



**请求体**：

```JSON
{ "action": "restart" }
```



**实现逻辑**：

1. 更新 `status = 'abandoned'`

2. 设置 `finished_at = NOW()`

3. 历史消息保留不删除

    

---



### 3\.7 POST /api/v1/interviews/\{sessionId\}/pause



**说明**：暂停访谈。



**请求体**：

```JSON
{ "action": "pause" }
```



**实现逻辑**：更新 `status = 'paused'`，保存当前 `current_phase` 和 `collect_*` 状态。



---



### 3\.8 GET /api/v1/interviews/active



**说明**：检测当前用户是否有进行中或暂停的访谈。



**响应体**：

```JSON
{
  "code": 200,
  "data": {
    "hasActive": true,
    "sessions": [{
      "sessionId": "uuid",
      "topic": "搞定说太贵了的客户",
      "status": "in_progress",
      "currentPhase": "storytelling",
      "lastActiveAt": "2026-06-28T14:30:00"
    }]
  }
}
```



**实现逻辑**：查询当前用户空间下 `status IN ('created','in_progress','paused')` 的会话。



---



## 4\. 报告模块



### 4\.1 GET /api/v1/reports



**说明**：获取报告列表。



**请求参数**：



|参数|类型|必填|说明|
|---|---|---|---|
|spaceId|string|否|按空间筛选|
|page|int|否|页码，默认1|
|size|int|否|每页条数，默认20|



**实现逻辑**：查询 `report` 表 → 支持按 `spaceId` 筛选 → 分页返回。



---



### 4\.2 GET /api/v1/reports/\{reportId\}



**说明**：获取报告完整详情。



**实现逻辑**：

1. 查询 `report` 表

2. 校验权限（报告所属空间公开 or 作者本人 or 超管）

3. 更新 `view_count + 1`

4. 返回完整 `content_json` \+ 下载链接

    

---



### 4\.3 PUT /api/v1/reports/\{reportId\}



**说明**：编辑报告内容。



**请求体**：

```JSON
{
  "chapters": [{ "order": 1, "content": "修改后的内容..." }],
  "regenerate": true
}
```



**实现逻辑**：

1. 校验权限（作者本人 or 超管）

2. 更新 `content_json`

3. 如果 `regenerate=true`：异步重新生成Word/PPT → `file_status='synced'`

4. 否则：`file_status='pending_regenerate'`

    

---



### 4\.4 GET /api/v1/reports/\{reportId\}/download



**说明**：下载Word或PPT版本。



**请求参数**：



|参数|类型|必填|说明|
|---|---|---|---|
|format|string|是|`word` 或 `ppt`|



**实现逻辑**：

1. 如果 `file_status='synced'`：直接返回已有文件

2. 如果 `file_status='pending_regenerate'`：触发重新生成 → 返回文件流 → 更新 `file_status='synced'`

    

---

### 4.5 POST /api/v1/reports/{reportId}/rate

**说明**：员工为报告打分（1-5星）。

**请求体**：
```JSON
{"rating": 4.5}
```

|参数|类型|必填|说明|
|---|---|---|---|
|rating|number|是|评分，范围 1.0-5.0|

**实现逻辑**：更新 report.rating。Controller: ReportController.rateReport()。

    

### 4.6 POST /api/v1/reports/{reportId}/checklist

**说明**：同步行动检查清单勾选状态。

**请求体**：
```JSON
{"checklist": [{"id": "item-1", "checked": true}]}
```

**实现逻辑**：持久化清单状态，同步 localStorage 与后端。Controller: ReportController.syncChecklist()。

    

---

## 5. 分身模块



### 5\.1 POST /api/v1/skills/\{skillId\}/chat（SSE）



**说明**：向销冠AI分身提问。



**请求体**：

```JSON
{
  "message": "客户说太贵了怎么回",
  "sessionId": "optional-session-id",
  "channel": "im",
  "mode": "quick"
}
```



|参数|类型|必填|说明|
|---|---|---|---|
|message|string|是|用户提问内容|
|sessionId|string|否|会话标识，用于连续对话|
|channel|string|否|`web` / `im`|
|mode|string|否|`quick` / `discuss` / `practice`，不传则自动识别|

当 `mode=quick` 时，分身回答末尾附带隐藏的 `grainId` 标记，格式为 `[grain:uuid]`。
当用户回复"展开"时，IM网关提取此 `grainId`，以 `mode=discuss` 重新调用本接口。

**实现逻辑**：

1. 校验 `skillId` 存在

2. 从 `experience_grain` 表加载该空间所有锦囊（或根据 `admin/config` 的 `reportScope` 筛选）

3. 按场景分组拼接 `{experience_context}`

4. 组装System Prompt：`skill_qa_chat.md` 基础模板 \+ 渠道指令 \+ 模式指令

5. 如果传入 `sessionId`：从Redis加载最近5轮历史（过期30分钟）

6. 调用大模型 → SSE流式返回

7. 将本轮对话存入Redis（含 `[grain:uuid]` 标记供后续"展开"识别）

    

---



### 5\.2 POST /api/v1/skills/\{skillId\}/practice/start



**说明**：开始实战对练。



**请求体**：

```JSON
{
  "scene": "破冰",
  "customScene": "自定义场景描述"
}
```



**实现逻辑**：

1. 如果传入 `customScene`：使用自定义场景

2. 否则：从报告 `content_json.chapters[5].practice_scene` 中读取预设场景

3. 返回场景设定和客户首句台词

    

---



### 5\.3 POST /api/v1/skills/\{skillId\}/practice/respond（SSE）



**说明**：对练中员工回应后，分身评判并示范。



**请求体**：

```JSON
{
  "practiceId": "uuid",
  "message": "吴总，我认真读了您..."
}
```



**实现逻辑**：

1. 加载对练上下文（场景设定 \+ 历史回合）

2. 调用大模型进行评判（优点\+改进\+示范话术）

3. SSE流式返回

    

---



### 5\.4 POST /api/v1/skills/enterprise/chat（SSE）



**说明**：企业总调度综合分身。



**实现逻辑**：

1. 在企业所有已发布且公开的空间中

2. 对 `experience_grain` 做向量语义检索

3. 每个销冠取Top5条锦囊

4. 按相关度排序

5. 构建综合Prompt → 流式返回（标注每条建议来源）

    

---



### 5\.5 POST /api/v1/skills/\{skillId\}/feedback



**说明**：提交回答反馈。



**请求体**：

```JSON
{
  "sessionId": "feishu_private_zhangsan",
  "grainId": "grain-uuid-001",
  "helpful": true
}
```



**实现逻辑**：

1. 更新 `experience_grain` 的 `helpful_count` 或 `unhelpful_count`

2. 定期汇总，更新 `report.rating`

    

---

### 5.6 GET /api/v1/skills/list

**说明**：分身广场——返回所有已激活分身的列表（含所有者姓名、锦囊数量、场景标签）。

**响应示例**：
```JSON
{
  "code": 200,
  "data": [{
    "skillId": "k001",
    "ownerName": "周铭",
    "ownerTitle": "华东区销冠",
    "styleTags": ["银行", "B2B"],
    "sceneTags": [{"tag": "破冰", "count": 8}],
    "grainCount": 24,
    "status": "published"
  }]
}
```

**实现逻辑**：查询所有 status=active 的 skill，关联 space.user 获取 owner 信息，从 experience_grain 统计 scene_tag。Controller: SkillController.listAllSkills()。

    

### 5.7 PUT /api/v1/skills/{skillId}/status

**说明**：管理员切换分身状态（公开/停用）。仅 super_admin 可调用。

**请求体**：
```JSON
{"status": "discarded"}
```

|参数|类型|必填|说明|
|---|---|---|---|
|status|string|是|"published" 或 "discarded"|

**实现逻辑**：更新 skill.status。Controller: SkillController.updateSkillStatus()。

    

### 5.8 GET /api/v1/skills/{skillId}/practice-scenes

**说明**：获取该分身的对练场景列表（从锦囊库按 scene_tag 分组生成）。

**响应示例**：
```JSON
{
  "code": 200,
  "data": [{
    "label": "破冰",
    "title": "第一次见银行客户",
    "setting": "你是销售，我是银行科技部吴总",
    "customerLine": "已经有两家在谈，不考虑新的",
    "grainCount": 8
  }]
}
```

**实现逻辑**：从 experience_grain 按 scene_tag 分组，每组生成一个场景。Controller: SkillController.getPracticeScenes()。

    

### 5.9 POST /api/v1/skills/{skillId}/practice/evaluate

**说明**：结束对练，AI 以销冠视角综合评价。

**请求体**：
```JSON
{
  "conversation": [{"role": "customer", "content": "太贵了"}, {"role": "me", "content": "..."}],
  "scene": "破冰-第一次见银行客户"
}
```

**响应**（SSE 流式）：
```JSON
{"score": 82, "strengths": ["准确捕捉了客户恐惧"], "improvements": ["可以更早抛出钩子"], "demo_script": "销冠示范话术...", "next_advice": "下次试试..."}
```

**实现逻辑**：使用 promptLoader.format("skill_practice_evaluate.md") 构建评价 Prompt。Controller: SkillController.evaluatePractice()。

    

---

## 6. 工具模块



### 6\.1 GET /api/v1/tools



**说明**：获取资料库列表。



**请求参数**：



|参数|类型|必填|说明|
|---|---|---|---|
|spaceId|string|否|按空间筛选|
|type|string|否|按类型筛选|



**实现逻辑**：查询 `tool` 表，支持按空间和类型过滤。



---



### 6\.2 GET /api/v1/tools/\{toolId\}/download



**说明**：下载指定资料。



**实现逻辑**：查询 `tool` 表 → 返回文件流。



---



## 7\. 管理模块



### 7\.1 GET /api/v1/admin/spaces



**说明**：获取企业所有空间管理列表（仅超管）。



**请求参数**：page, size, keyword, status



**实现逻辑**：查询当前企业下所有空间 → 支持搜索和状态筛选。



---



### 7\.2 GET /api/v1/admin/scene\-coverage



**说明**：获取场景覆盖数据。



**实现逻辑**：

1. 查询所有 `experience_grain`，按 `scene_tag` 分组统计

2. 覆盖率 ≥3 → `sufficient`；1\-2 → `moderate`；0 → `empty`

3. 空白场景：推荐该场景下表现最好的销冠（通过 `skillCallCount` 或手动标记）

    

---



### 7\.3 PUT /api/v1/admin/config



**说明**：更新企业配置。



**请求体**：

```JSON
{
  "logoUrl": "https://...",
  "brandColor": "#1A2B4C",
  "modelConfig": {
    "interviewModel": "deepseek-chat",
    "reportModel": "deepseek-chat",
    "skillModel": "deepseek-chat",
    "apiKey": "sk-..."
  },
  "imConfig": {
    "feishu": { "enabled": true, "webhookUrl": "..." }
  },
  "skillConfig": {
    "reportScope": "all",
    "selectedReports": []
  }
}
```



**实现逻辑**：更新 `company` 表相关字段 → 更新Redis缓存 → 下次访谈/分身调用时生效。



---



### 7\.4 POST /api/v1/admin/invite



**说明**：生成萃取邀请链接。



**请求体**：

```JSON
{
  "userId": "chen-ting-uuid",
  "sceneTag": "转介绍"
}
```



**实现逻辑**：

1. 生成唯一邀请码（UUID截取前8位）

2. 存入Redis（key=inviteCode, value=userId\+sceneTag, 过期7天）

3. 返回完整链接

    

---



## 8\. 专家经验库模块



### 8\.1 GET /api/v1/experts/available



**说明**：获取可用萃取师列表（所有已登录用户可调用）。



**响应体**：

```JSON
{
  "code": 200,
  "data": [
    { "id": "uuid", "name": "综合（使用所有萃取师）", "type": "composite" },
    { "id": "uuid-zhang", "name": "张萃取师（追问型·金融）", "type": "single", "styleTags": ["追问型"], "industryTags": ["金融"] },
    { "id": "none", "name": "不使用萃取师经验（基础版）", "type": "none" }
  ]
}
```



**实现逻辑**：查询 `expert_skill` 表中 `status='active'` 的记录 → 组装列表（综合项\+各萃取师\+基础版）。



---



### 8\.2 GET /api/v1/admin/experts



**说明**：获取萃取师管理列表（仅超管）。



**请求参数**：page, size, keyword, industry, status



**实现逻辑**：查询 `expert_skill` 表 → 支持搜索和筛选 → 分页返回。



---



### 8\.3 GET /api/v1/admin/experts/\{expertId\}



**说明**：获取萃取师详情（含锦囊列表和文档列表）。



**实现逻辑**：

1. 查询 `expert_skill` 表

2. 关联查询 `expert_grain` 表（按category分组）

3. 关联查询 `expert_document` 表

4. 关联查询 `report` 表（获取报告内容）

    

---



### 8\.4 POST /api/v1/admin/experts/upload



**说明**：上传萃取师材料，触发AI分析。



**请求**：`multipart/form-data`



|参数|类型|必填|说明|
|---|---|---|---|
|name|string|是|萃取师名称|
|description|string|否|简介|
|styleTags|string\[\]|否|风格标签|
|industryTags|string\[\]|否|擅长行业|
|seniority|string|否|资历|
|files|File\[\]|是|文件列表（PDF/Word/PPT/TXT/MD，单文件上限50MB）|



**实现逻辑**：

1. 创建 `expert_skill` 记录（`status='pending'`）

2. 文件存入本地存储（`/data/files/experts/{expertId}/`）

3. 文件记录存入 `expert_document` 表（`status='uploaded'`）

4. 更新 `status='analyzing'`

5. 异步调用Python AI服务：

    - 使用 `expert_document_extraction.md` Prompt

    - 传入所有文件内容

    - 生成《萃取师经验报告》

6. 报告存入 `report` 表 → 更新 `expert_skill.report_id` 和 `status='extracting'`

    

---



### 8\.5 POST /api/v1/admin/experts/\{expertId\}/extract



**说明**：从报告中提取锦囊。



**实现逻辑**：

1. 查询该萃取师的报告（`content_json`）

2. 调用 `expert_extractor.py`：

    - 从七章中逐章提取

    - 每条经验生成 `expert_grain` 记录（`status='under_review'`）

    - 标注 `source_type='document'`

    - 标注可信度（多材料验证=高，单一来源=待验证）

3. 更新 `expert_skill.grain_count`

    

---



### 8\.6 PUT /api/v1/admin/experts/\{expertId\}/grains/\{grainId\}



**说明**：编辑单条锦囊。



**请求体**：

```JSON
{
  "category": "judgment_intuition",
  "applicationRule": "修改后的规则文本",
  "priority": 5,
  "status": "published"
}
```



**实现逻辑**：更新 `expert_grain` 表对应记录。



---



### 8\.7 DELETE /api/v1/admin/experts/\{expertId\}/grains/\{grainId\}



**说明**：删除单条锦囊。



**实现逻辑**：删除 `expert_grain` 表对应记录。



---



### 8\.8 POST /api/v1/admin/experts/\{expertId\}/activate



**说明**：确认审核通过，激活Skill。



**实现逻辑**：

1. 将该萃取师下所有 `under_review` 锦囊改为 `active`

2. 调用 `expert_extractor.generate_expert_md_file()` 生成MD文件

3. 调用 `expert_composer.py` 更新 `expert_composite.md`

4. 更新 `expert_skill.status='active'`

    

---



### 8\.9 POST /api/v1/admin/experts/\{expertId\}/documents



**说明**：追加文件材料。



**请求**：`multipart/form-data`



|参数|类型|必填|说明|
|---|---|---|---|
|files|File\[\]|是|追加的文件列表|



**实现逻辑**：

1. 验证萃取师存在（`status` 为 `active` 或 `extracting`）

2. 新文件存入本地存储 → 记录到 `expert_document` 表

3. 异步AI分析仅新文件

4. 新提取的锦囊状态为 `under_review`，已有 `active` 锦囊不变

5. 更新 `expert_skill.status='extracting'`

    

---



### 8\.10 DELETE /api/v1/admin/experts/\{expertId\}/documents/\{documentId\}



**说明**：删除指定文件。



**实现逻辑**：

1. 删除 `expert_document` 记录 \+ 本地文件

2. 查询从该文件提取的锦囊：

    - `under_review` → 废弃（`deprecated`）

    - `active` → 保留，记录"来源文件已删除"

3. 返回受影响锦囊数量

    

---



### 8\.11 PUT /api/v1/admin/experts/\{expertId\}/documents/\{documentId\}



**说明**：替换指定文件。



**请求**：`multipart/form-data`



|参数|类型|必填|说明|
|---|---|---|---|
|file|File|是|替换的新文件|



**实现逻辑**：

1. 删除旧文件 \+ 旧 `expert_document` 记录

2. 旧文件锦囊处理（同删除逻辑）

3. 上传新文件 → 创建新记录 → 异步AI分析 → 生成新锦囊

    

---



### 8\.12 POST /api/v1/admin/experts/composite/regenerate



**说明**：重新生成综合Skill文件。



**实现逻辑**：

1. 加载所有 `status='active'` 的萃取师锦囊

2. 按 `category` 分组 → 比对 `application_rule` 语义相似度

3. 执行协同策略（共识/独家/矛盾）

4. 重新生成 `expert_composite.md`

5. 更新版本号

    

---



### 8\.13 GET /api/v1/admin/experts/composite



**说明**：获取当前综合Skill详情。



**实现逻辑**：读取 `expert_composite.md` 文件 → 返回版本号、萃取师统计、共识/独家/矛盾数量、内容预览。



---

### 8.14 PUT /api/v1/admin/experts/documents/{docId}

**说明**：更新文档的解析内容。用于图片/音频文件经人工处理后，手动填写解析结果。仅 super_admin 可调用。

**请求体**：
```JSON
{"parsedContent": "手动整理的文本内容..."}
```

|参数|类型|必填|说明|
|---|---|---|---|
|parsedContent|string|是|人工处理后的文本内容|

**实现逻辑**：
1. 校验文档存在且当前 status=pending_manual
2. 更新 expert_document.parsed_content 和 status="parsed"
3. 将关联的 expert_skill.status 重置为 pending，触发调度器重新分析

Controller: ExpertController.updateDocument().

    

---

## 9. IM集成模块



### 9\.1 POST /api/v1/im/\{channel\}/callback



**说明**：接收IM平台消息回调。



**实现逻辑**：

1. 验证签名

2. 解析消息（用户ID、问题、群聊/私聊、@的机器人）

3. 匹配 `im_channel` 表 → 获取 `skillId`

4. 生成 `sessionId = "{channel}_{chatId}_{userId}"`

5. 检测首次对话：

    - Redis中无历史记录 → 返回欢迎消息 → 存入Redis

6. 识别对话模式（/对练→practice, /讨论→discuss, 默认→quick）

7. 调用 `POST /skills/{skillId}/chat`（内部调用）

8. 转成IM消息格式 → 调用IM API回复（群聊中@提问者）

9. 对话存入Redis（过期30分钟）

    

---



### 9\.2 GET /api/v1/im/channels



**说明**：获取IM渠道列表。



**实现逻辑**：查询 `im_channel` 表（当前企业）。



---



### 9\.3 POST /api/v1/im/channels



**说明**：新增IM渠道。



**请求体**：

```JSON
{
  "channelType": "feishu",
  "enabled": true,
  "config": { "appId": "cli_xxx", "appSecret": "xxx", "webhookUrl": "https://..." },
  "linkedSkills": ["skill-uuid-001"]
}
```



**实现逻辑**：插入 `im_channel` 表。



---



### 9\.4 PUT /api/v1/im/channels/\{id\}



**说明**：编辑IM渠道。



**实现逻辑**：更新 `im_channel` 表。



---



### 9\.5 DELETE /api/v1/im/channels/\{id\}



**说明**：删除IM渠道。



**实现逻辑**：删除 `im_channel` 表记录。



---



### 9\.6 POST /api/v1/im/channels/\{id\}/test



**说明**：测试IM渠道连接。



**实现逻辑**：向IM平台发送一条测试消息 → 返回成功或失败信息。



---



**文档版本**：V1\.0  

**日期**：2026年6月29日  

**接口总数**：56+个

