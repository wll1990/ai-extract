# 三方 App / H5 接入指南


> 面向：第三方合作方的服务端与客户端研发。
> 状态：v1.0 草案，接口以联调环境为准。
> 目标：三方 App 通过 WebView/iframe 嵌入我方 H5 聊天、访谈、报告页面，用户无感登录。

## A.1 接入模式

三方**服务端**用 `appId + SK` 调用我方接口，换取一个 **60 秒、单次使用**的 `code`；三方把 `code` 放到 H5 URL 的 **fragment** 中打开页面；H5 用 `code` 换取会话 token，后续聊天/SSE 自动携带。

- SK 只保存在三方服务端，**禁止**下发到 App、H5、日志。
- 不使用长效 JWT，不把 token 放在初始 URL。
- 会话由我方统一管理，可按用户踢下线、可查设备。

## A.2 接入前准备

联系我方开通 `PartnerApp`，获得：

| 项 | 说明 |
|---|---|
| `appId` | 合作方唯一标识（UUID） |
| `SK` | 签名密钥，仅创建时展示一次；支持轮换 |
| 嵌入域名白名单 | 用于 CSP `frame-ancestors`，未配置默认拒绝 iframe |
| H5 入口地址 | 按业务场景（聊天/访谈/报告）提供 |
| 测试/生产 Base URL | 联调时提供 |

要求：
- 服务端启用 NTP，时钟误差 < 5 分钟。
- 全链路 HTTPS。
- SK 放环境变量/密钥管理，不写代码、不进日志。

## A.3 接入流程

```
三方 App                三方服务端                    我方后端                    H5（我方域名）
   |                       |                             |                          |
   | 打开 H5/用户点击       |                             |                          |
   |---------------------->|                             |                          |
   |                       | 1. 签发 code（HMAC 签名）    |                          |
   |                       |---------------------------->|                          |
   |                       |<--- {code, expiresIn:60} ---|                          |
   |                       |                             |                          |
   | 2. 打开 H5 URL#code=...                            |                          |
   |----------------------------------------------------------------------------->|
   |                       |                             |  3. code 换会话 token     |
   |                       |                             |<-------------------------|
   |                       |                             |--- {token, expiresIn} -->|
   |                       |                             |                          |
   |                       |                             |  4. 聊天/SSE（Bearer）    |
   |                       |                             |<-------------------------|
```

关键约束：
- 一个 `code` 只能兑换一次，60 秒过期；**每次打开 H5 都重新签发**。
- `code` 放 URL fragment（`#code=`），不放 query（`?code=`）。
- H5 读取后立即清除 fragment，不写入历史记录。

## A.4 签名规范

### A.4.1 请求头

| Header | 必填 | 说明 |
|---|---|---|
| `X-Partner-App-Id` | 是 | 合作方 appId |
| `X-Partner-Timestamp` | 是 | 当前时间毫秒时间戳（13 位） |
| `X-Partner-Nonce` | 是 | 随机 UUID，10 分钟内不可重复 |
| `X-Partner-Signature` | 是 | HMAC-SHA256 小写 hex |
| `X-Request-Id` | 否 | 请求唯一 ID，便于排障 |
| `Content-Type` | 是 | `application/json` |

### A.4.2 待签名串

按 `\n` 拼接，**顺序固定**：

```
METHOD
PATH
X-Partner-Timestamp
X-Partner-Nonce
lowercase_hex(SHA256(rawBody))
```

- `METHOD`：大写，如 `POST`。
- `PATH`：含上下文路径，如 `/api/v1/internal/partner/session-codes`，不含域名和 query。
- `rawBody`：实际发送的 JSON 字节；空 body 用空串。
- 签名：`lowercase_hex(HMAC-SHA256(SK, 待签名串))`。
- 比较使用常量时间；时间窗 ±5 分钟；nonce 10 分钟内不可重复。

### A.4.3 Node.js 示例

```js
const crypto = require('crypto');

const appId = process.env.PARTNER_APP_ID;
const sk = process.env.PARTNER_SK;
const base = 'https://<host>/api/v1';
const path = '/internal/partner/session-codes';
const body = JSON.stringify({
  externalUserId: 'user-123',
  userName: '张三',
  deviceId: 'android-abc',
  ttlSeconds: 7200
});

const timestamp = Date.now().toString();
const nonce = crypto.randomUUID();
const bodyHash = crypto.createHash('sha256').update(body, 'utf8').digest('hex');
const canonical = ['POST', `/api/v1${path}`, timestamp, nonce, bodyHash].join('\n');
const signature = crypto.createHmac('sha256', sk).update(canonical, 'utf8').digest('hex');

const res = await fetch(base + path, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-Partner-App-Id': appId,
    'X-Partner-Timestamp': timestamp,
    'X-Partner-Nonce': nonce,
    'X-Partner-Signature': signature,
    'X-Request-Id': crypto.randomUUID()
  },
  body
});
```

## A.5 接口一：签发会话 code

```http
POST /api/v1/internal/partner/session-codes
```

请求体：

```json
{
  "externalUserId": "user-123",
  "userName": "张三",
  "deviceId": "android-abc",
  "ttlSeconds": 7200
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `externalUserId` | 是 | 三方用户唯一 ID，≤128 字符；同一 appId 下唯一 |
| `userName` | 否 | 展示昵称，≤64 字符 |
| `deviceId` | 否 | 设备标识，≤64 字符；不传则用 UA 派生 |
| `ttlSeconds` | 否 | 会话有效期，600–43200，默认 7200 |

成功响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "code": "3f9c...（32 字节随机串）",
    "expiresIn": 60
  }
}
```

注意：
- `code` 只返回一次，不缓存、不落库、不进日志。
- 每次打开 H5 重新签发；不要复用。
- 同一用户短时间内多次打开会产生多个会话，默认最多 5 个设备，超出淘汰最早登录。

## A.6 拉起 H5

URL 格式：

```
<H5_ENTRY_URL>#code=<CODE>
```

示例：

```
https://<host>/h5/interview/m/abc123#code=3f9c...
```

要求：
- **必须使用 fragment `#code=`**，不要用 `?code=`；fragment 不会发送到服务端，也不会进 Referer。
- H5 读取后会立即 `history.replaceState` 清除 `code`；三方不要自行保存/打印完整 URL。
- 若 WebView 不支持 fragment 或使用 iframe，可改用双方联调约定的 JS bridge 传递 `code`；不要通过 `window.name`、Cookie 等隐式通道传递。
- iframe 嵌入时，页面域名必须在我方配置的 `frame-ancestors` 白名单内，否则会被浏览器拒绝。

Android WebView 参考：

```java
webView.getSettings().setJavaScriptEnabled(true);
webView.getSettings().setDomStorageEnabled(true); // H5 用 localStorage 保存会话
webView.loadUrl(h5Url + "#code=" + code);
```

iOS WKWebView 参考：

```swift
let url = URL(string: "\(h5Url)#code=\(code)")!
webView.load(URLRequest(url: url))
```

## A.7 会话生命周期

| 项 | 默认值 | 说明 |
|---|---|---|
| 闲置过期 | 2 小时 | 有请求自动续期 |
| 绝对过期 | 12 小时 | 从建立会话起算，不因活跃延长 |
| 多设备 | 5 台 | 超出淘汰最早登录 |
| 刷新页面 | 复用会话 | token 存 localStorage；不要重新签发 |
| 会话失效 | 401 | H5 提示重新进入；三方重新签发 code |
| 登出 | `POST /auth/logout` | 只撤销当前会话；也可调 A.8 踢全端 |

- 会话 token 只在 H5 内使用，不要回传三方服务端。
- 三方用户身份变更（换账号）必须重新签发 code，不能复用旧 code。
- 合作方被禁用时，所有会话下次请求 403/401。

## A.8 会话治理接口（三方服务端调用）

均使用 A.4 的 HMAC 签名，且**只能操作本 appId 下的用户**。

### 查询用户设备

```http
GET /api/v1/internal/partner/sessions?externalUserId=user-123
```

```json
{
  "code": 200,
  "data": {
    "sessions": [
      {
        "sessionId": "uuid",
        "deviceId": "android-abc",
        "userAgent": "Mozilla/5.0 ...",
        "ip": "1.2.3.4",
        "createdAt": "2026-11-18T10:00:00Z",
        "lastSeenAt": "2026-11-18T10:30:00Z"
      }
    ]
  }
}
```

### 踢单设备

```http
DELETE /api/v1/internal/partner/sessions/{sessionId}
```

### 踢该用户全端

```http
POST /api/v1/internal/partner/sessions/revoke-all
Content-Type: application/json

{"externalUserId":"user-123"}
```

- 踢下线在**下一次请求**生效；已建立的 SSE 长连接不会被立即中断。
- 越权（sessionId 不属于该 appId/externalUserId）返回 404，不泄露存在性。

## A.9 嵌入安全要求

1. **域名白名单**：iframe 嵌入域名必须提前报备，我方通过 CSP `frame-ancestors` 限制；未报备默认拒绝。
2. **不依赖第三方 Cookie**：H5 使用 `Authorization` / localStorage，Safari ITP、Chrome 第三方 Cookie 限制不影响。
3. **Referrer-Policy**：H5 页面返回 `no-referrer`；三方页面不要把我方 H5 URL 放进可被外部引用的位置。
4. **禁止记录敏感信息**：三方服务端日志不得记录 `SK`、`code`、会话 token；H5 URL 不要上报到三方埋点。
5. **WebView 安全**：关闭不必要的 JS bridge；如使用 bridge，只传 `code`，不传 `SK`。
6. **防截图/录屏**：如业务需要，由三方 App 侧处理；我方 H5 不依赖此能力。

## A.10 错误码

| HTTP | 场景 | 处理建议 |
|---|---|---|
| 200 | 成功 | — |
| 400 | 参数缺失/格式错误 | 修正参数 |
| 401 | 签名错误、时间戳超出 ±5 分钟、nonce 重复 | 校准时钟/重新生成 nonce |
| 401 | code 过期或已使用 | 重新签发 code |
| 403 | 合作方已禁用、域名未报备 | 联系我方 |
| 404 | 会话不存在或不属于该 appId | 不要重试；检查参数 |
| 429 | 配额/频率超限 | 退避后重试，使用新 nonce |
| 503 | 服务暂不可用 | 指数退避重试 |

说明：`message` 用于展示/排障，业务分支请以 HTTP 状态 + 场景为准。

## A.11 限流与配额

- 按 `appId` 限制 code 签发 QPS、日活用户数、并发会话数、单用户设备数。
- code 签发失败可重试，但每次必须使用**新的 timestamp + nonce**。
- code 兑换失败不要盲目重试；先判断是否过期/已使用，必要时重新签发。
- 超限返回 429；请做指数退避，不要高频轮询。

## A.12 联调清单

- [ ] SK 只配置在三方服务端，未下发 App/H5
- [ ] 签名串顺序、body hash、小写 hex 正确
- [ ] 时间戳为 13 位毫秒，NTP 正常
- [ ] nonce 每次唯一，重放被拒
- [ ] code 60 秒过期、只能兑换一次
- [ ] H5 使用 `#code=`，读取后清除 fragment
- [ ] H5 刷新复用会话，不重复兑换
- [ ] 聊天/SSE 正常，401 能提示重新进入
- [ ] 踢单端/踢全端下一次请求生效
- [ ] iframe 域名白名单生效，未报备域名被拒
- [ ] 三方日志无 SK/code/token
- [ ] 429/503 有退避重试

## A.13 FAQ

**Q：为什么不用 JWT？**
A：JWT 放在 URL/WebView 里泄露后无法按单用户撤销；code 短时单次，会话可统一踢下线。

**Q：H5 刷新后要重新签发 code 吗？**
A：不需要。会话 token 存在 localStorage，刷新复用；只有 401（过期/被踢）时才重新签发。

**Q：用户换设备/换账号怎么办？**
A：换账号必须重新签发 code；换设备会新建会话，默认最多 5 台。

**Q：会话快过期了，能静默续期吗？**
A：有请求会自动续期闲置时间；绝对过期（12 小时）后必须重新签发 code。暂不提供 refresh token。

**Q：三方后端能直接调我方聊天 API 吗？**
A：不能。聊天/SSE 只在 H5 内使用会话 token；三方后端只调 A.5/A.8 的 HMAC 接口。

**Q：签名总是不通过怎么排查？**
A：按顺序核对：PATH 是否含 `/api/v1`、body 是否与实际发送字节一致、时间戳是否 13 位、nonce 是否重复、SK 是否当前有效、hex 是否小写。

## A.14 版本记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 草案 | 2026-11-18 | 首版：code exchange、会话治理、嵌入安全 |
