# agent-platform · 薄内核骨架

通用 Agent 平台的可运行实现。**不是设计稿，是能 `mvn test` 全绿的代码。**

设计基线：《通用 Agent 平台 · 开发设计总纲（薄内核版）》。
本文是实现口径与验收对照。

---

## 1. 这一版有什么

| 设计基线里的东西 | 本仓库对应 | 状态 |
|---|---|---|
| 薄内核（`Ctx` 门面 + `SeamRegistry` + `EventBus`） | `kernel/` | ✅ 可跑 |
| 9 条能力缝接口 | `seam/` | ✅ 声明齐（阶段一 7 条可跑，阶段二 2 条留接口） |
| append-only 会话事件日志 | `session/` | ✅ 含 AG-UI 投影 |
| Agent 循环（ReAct + 预算 + 中断安全） | `loop/` | ✅ 含幻觉防护与失败不穿透 |
| 工具执行管线（5 道判断） | `tools/` | ✅ |
| 危险命令策略 | `sandbox/CommandPolicy` | ✅ |
| 沙箱：容器 / 进程双实现 | `sandbox/` | ✅ 容器不可用自动降级 |
| 上下文预算 + 分层压缩 + 双记录 | `context/` | ✅ |
| OpenAI 兼容适配器（D1） | `llm/OpenAiCompatibleAdapter` | ✅ 零 SDK 依赖 |
| **MySQL 持久化（U18）** | `store/JdbcStore` + `store/StoreFactory` | ✅ 同一份 SQL 跑 MySQL / H2；事件、快照、幂等键重启不丢 |
| 内存 Store（MySQL 的契约参照） | `store/InMemoryStore` | ✅ 不配数据库就是它，零外部依赖 |
| 装配根 | `run/Platform` | ✅ |
| **HTTP + SSE 传输层（U02）** | `web/` | ✅ 零新增依赖（JDK HttpServer + 虚拟线程） |
| **AG-UI 规范端点（U08）** | `session/AgUiProjector` + `POST /agui/run` | ✅ 事件形状符合规范，客户端可直连 |
| **断线续传（U09）** | `web/EventPump` | ✅ 机制已验（无缺无重） |
| **API Key 鉴权（U16）** | `web/ApiKeyGuard` | ✅ 三种携带方式；前端三条路都带 key |
| **审计 + 限流（U17）** | `web/AuditLog` · `web/RateLimiter` | ✅ 谁/何时/调什么可追溯；按调用方令牌桶限流 |
| **工具策略（U15）** | `tools/ToolPolicy` + `ToolSpec.executing` | ✅ 工具级权限 + 危险命令，**不靠工具名** |
| **前端聊天面（U07a）** | `ui/`（Vite + React + CopilotKit） | ✅ 直连 `/agui/run`，已用真浏览器验过 |
| **工具卡片 + 过程时间线（U07b）** | `ui/src/ToolCard.tsx` · `ui/src/Timeline.tsx` | ✅ 工具参数/结果可视化；原始事件实时可见 |
| **人工确认 HITL（U12/U13）** | `hitl/InteractiveHitl` + `ui/src/ApprovalPanel.tsx` | ✅ once / always / deny / modify / timeout 五条路径，全程留痕 |

**尚未做**（按计划属后续需求单元）：MCP 接入（U24）、耐久状态机（U19/U20）、
RBAC（U25）、多模态与协作（U27+）、可观测台（U23）。

---

## 2. 跑起来

前置：JDK 21+。**不需要预装 Maven** —— 仓库自带 wrapper（`mvnw`），首次运行会自动下载 Maven 3.9.16。

```bash
# 离线演示：脚本化模型 + 进程沙箱。不需要网络、不需要 Docker
./mvnw -q compile exec:java@demo

# 起 HTTP 服务（U02）：默认 http://127.0.0.1:8787
./mvnw -q compile exec:java@serve

# 前端（U07a）：只需构建一次，产物由上面的服务托管在 /ui/
cd ui && npm install && npm run build && cd ..

# 单元测试（不需要数据库，也不需要 Docker）
./mvnw test

# 带持久化起服务（U18）：先建库，再把 URL 给它
#   mysql -u root -e 'CREATE DATABASE aplat CHARACTER SET utf8mb4'
export APLAT_DB_URL='jdbc:mysql://127.0.0.1:3306/aplat'
export APLAT_DB_USER=root APLAT_DB_PASSWORD=你的密码
./mvnw -q compile exec:java@serve
```

> **不设 `APLAT_DB_URL` 就是内存实现**（启动横幅会标 `⚠ 重启即丢`）。
> 配了库却连不上时**启动直接失败**——悄悄退回内存是最糟的结果：
> 服务跑得好好的，直到某次重启才发现数据全没了。

> `exec:java@serve` 这个写法是必须的：POM 的 `<configuration>` 里显式写了 `mainClass`，
> 此时 `-Dexec.mainClass=` **会被忽略**（配置优先级高于用户属性），
> 所以两个入口做成了两个 execution id（`demo` / `serve`）。裸跑 `exec:java` 默认走 `demo`。

### 接口面

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/ui/` | **前端聊天面（U07a）**：CopilotKit + AG-UI 直连 |
| `POST` | `/agui/run` | **AG-UI 标准端点（U08）**：收 `RunAgentInput`，SSE 吐规范事件 |
| `GET` | `/` | 内置调试控制台（**不是正式前端**，用肉眼确认 SSE 链路） |
| `GET` | `/health` | 健康 + 装配自检（免鉴权，供探针） |
| `GET` | `/kernel` | 装配清单与可替换能力缝（排查第一站） |
| `GET` | `/tools` | 工具清单（名字/说明/是否需批准/是否执行命令）——**前端靠它渲染工具卡片，不硬编码工具名** |
| `GET` | `/audit?limit=N` | 审计：谁 / 何时 / 调了什么 / 结果（受鉴权保护——审计记录本身也是敏感信息） |
| `GET` | `/hitl/pending?sessionId=` | 谁在等人批准：工具 / 参数 / 理由 / 剩余时间 |
| `POST` | `/hitl/{requestId}` | 提交决定：`{"decision":"once\|always\|deny\|modify","arguments?"}` |
| `POST` | `/run` | `{"sessionId?","input"}` → 跑完一个 turn，返回 JSON |
| `GET` | `/agui/stream?input=&sessionId=` | 简化流：跑一个 turn 并 SSE 吐**本平台信封**事件（自带控制台用） |
| `GET` | `/agui/events/{sessionId}?lastEventId=` | 纯事件面：回填 + 实时，**断线续传**；加 `&raw=1` 则帧不带 `event:` 字段 |
| `GET` | `/sessions/{sessionId}/events` | 回放为 JSON（离线排查） |

> **`raw=1` 是给"要看全部事件"的客户端用的**（前端的过程时间线就走它）。
> SSE 里帧一旦带 `event:`，浏览器就按**具名事件**派发，`es.onmessage` 收不到——
> 于是只能枚举事件名，后端一加新类型界面就静默少一条。`raw=1` 让后端写无名帧，
> 全部走 `onmessage`，新事件自动出现。事件名没丢，`data` 里的 `type` 就是它。

**两种事件方言不要混**：`/agui/run` 吐的是 AG-UI **规范字段**（`threadId`/`messageId`/`delta`/
`toolCallId`），给 CopilotKit、`@ag-ui/client` 这类客户端用；`/agui/stream` 吐的是本平台信封
（`{type, sessionId, seq, payload}`），只给自带控制台和 curl 用。前端接的是前者。

```bash
# 同步跑一次
curl -s -X POST http://127.0.0.1:8787/run \
     -H 'Content-Type: application/json' \
     -d '{"input":"用 shell 打印当前目录"}'

# AG-UI 契约面（前端走的就是它）
curl -N -X POST http://127.0.0.1:8787/agui/run \
     -H 'Content-Type: application/json' \
     -d '{"threadId":"t1","runId":"r1","messages":[{"role":"user","content":"hello"}]}'

# 续传：从第 7 条之后补齐，看到 turn.closed 就收摊
curl -N 'http://127.0.0.1:8787/agui/events/s-abc?lastEventId=7&once=turn.closed'
```

**事件流与 turn 执行是两条独立入口**——`/agui/stream` 只是"订阅 + 触发一次 turn"的组合，
`/agui/events` 完全不碰循环。所以断线重连、多端同看、事后补看走的是同一条路，后端不需要做特例。

### 鉴权 / 审计 / 限流（U16 + U17）

```bash
export APLAT_API_KEY=dev-secret            # 不设 = 不鉴权（本机自查）
export APLAT_RATE_LIMIT_PER_MIN=30         # 0 = 不限
export APLAT_AUDIT_FILE=logs/audit.jsonl   # 不设则只留内存
./mvnw -q compile exec:java@serve
```

**免鉴权 / 免限流的只有**：`/health`、`/`、`/ui/*`、`/favicon.ico`。
**API 面（`/run`、`/agui/run`、`/agui/events`、`/tools`、`/audit`…）一律要 key 且计入限流**——
静态资源本身不含敏感信息，探针不该需要凭据；而真正产生副作用与泄露信息的是 API 面。

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8787/run \
     -H 'Content-Type: application/json' -d '{"input":"hi"}'                 # → 401
curl -s -X POST http://127.0.0.1:8787/run -H 'X-API-Key: dev-secret' \
     -H 'Content-Type: application/json' -d '{"input":"hi"}'                 # → 200
curl -s -H 'X-API-Key: dev-secret' 'http://127.0.0.1:8787/audit?limit=5'     # 审计
```

**审计身份用密钥指纹，不用原值**（SHA-256 前 8 位）。审计日志是"给很多人看"的东西，
往里写密钥原文等于把密钥抄送一遍——有一条测试专门钉这件事。

**限流是令牌桶**（不是固定窗口：那个在窗口边界会放行两倍流量），按调用方隔离。
未启用鉴权时按 **IP** 隔离，否则所有人共用一个 `anonymous` 桶、一个人刷满就把别人全挡住。
超限返回 `429` + `Retry-After`。

### 前端（U07a）

```bash
cd ui
npm install          # 首次约 6 分钟
npm run build        # tsc --noEmit + vite build，产物进 ui/dist（已 gitignore）
```

构建完重启服务，打开 <http://127.0.0.1:8787/ui/>。

开发态也可以 `npm run dev`（5173 端口，vite 代理 `/agui` 到 8787），改代码热更新。

**它是怎么接上后端的**：把 `HttpAgent` 实例直接交给 provider，浏览器直接跟 `/agui/run` 说话，
**没有 Node runtime 中间层**：

```tsx
const agents = { default: new HttpAgent({ url: `${location.origin}/agui/run` }) }
<CopilotKit agents__unsafe_dev_only={agents}><CopilotChat /></CopilotKit>
```

> ⚠️ **三条必须知道的限制**（都会影响 D6 的取舍）：
> 1. **`agents__unsafe_dev_only` 名字里就写着 dev-only**——生产要么走 `selfManagedAgents`
>    （CopilotKit 的付费档），要么架一个 CopilotKit runtime 做代理。直连只适合本地。
> 2. **直连意味着鉴权/CORS/限流全由我们自己的端点负责**，provider 不会替你带任何东西。
> 3. **体积不小**：`ui/dist` 约 18MB、426 个资源，入口 chunk 2.7MB
>    （CopilotKit 的 UI 带着 mermaid / cytoscape / 代码高亮）。本地自查无所谓，
>    真要对外就得配 code-splitting 或换更轻的自研面。
>
**开了鉴权之后**，右上角有个 API Key 输入框（存 `localStorage`）。注意 key 要带在**三条路**上，
漏掉任何一条那个功能区就静默失效：

| 路径 | 怎么带 |
|---|---|
| 聊天 → `POST /agui/run` | `HttpAgent` 的 `headers` |
| 时间线 → `GET /agui/events` | 查询参数 `?apiKey=`（`EventSource` **不能**自定义请求头） |
| 工具卡片 → `GET /tools` | `fetch` 的请求头 |

没填 key 时页脚会明确写"工具清单未加载：需要 API Key（右上角填）"，而不是给你一个空列表。

#### 工具卡片与过程时间线（U07b）

界面右侧是**过程时间线**：它订阅 `/agui/events/{threadId}?raw=1`，把后端产生的**原始会话事件**
逐条摊开。它和聊天面看的东西刻意不同——聊天面只该看到"前端需要的规范事件"，
而时间线要能看到被投影器有意过滤掉的内部记账事件（`CUSTOM_CONTEXT_PREPARED` 这类）。
排查"模型为什么忘了"时，答案恰恰在那里，不在聊天窗口里。

工具卡片则靠 `GET /tools` 动态生成渲染器（匹配规则是**精确工具名**，所以每个工具一条）。
展开能看到参数与结果；被策略拒绝的工具会标红——因为后端把失败也原样回填成
`ERROR[错误码]`，界面显示的与模型看到的完全一致。

> 类型上的两个坑（都靠 `tsc` 才发现的）：`status` 是**枚举**不是字符串字面量；
> 而那个枚举**没有从公开入口导出**。所以卡片改用联合类型自己的结构判别
> （`result !== undefined` ⇔ 调用已结束），不引用任何枚举成员。

### 持久化（U18）

```bash
export APLAT_DB_URL='jdbc:mysql://127.0.0.1:3306/aplat'
export APLAT_DB_USER=root APLAT_DB_PASSWORD=
./mvnw -q compile exec:java@serve
# Store   : store.jdbc[mysql]  ← 重启不丢（jdbc:mysql://127.0.0.1:3306/aplat）
```

四张表，语义一眼能看出来（`SHOW CREATE TABLE` 就够）：

| 表 | 存什么 | 为什么是它 |
|---|---|---|
| `aplat_events` | 会话事件（主键 `(session_id, seq)`） | 主键即"不重不乱"的保证，回放与续传都靠它 |
| `aplat_session_seq` | 每条会话的下一个序号 | 取号要**原子**，所以必须有独立的一行来加锁 |
| `aplat_snapshots` | 每条会话的最新检查点 | U19/U20 的崩溃恢复从这里读 |
| `aplat_idempotency` | 幂等键 → 引用 | 唯一主键即幂等，不需要应用层加锁 |

**同一份 SQL 既跑 MySQL 也跑 H2** —— 这不是巧合：整类只有**一处**方言假设
（取号用的 `ON DUPLICATE KEY UPDATE`），H2 只要开 `MODE=MySQL` 就支持，
而 URL 里没写 `MODE=MySQL` 时构造会直接报错（否则会在某次 append 上神秘地失败）。
所以"开发用 H2、部署用 MySQL"是**换一个 URL**，不是换一套代码。

**时间戳存 `BIGINT` 毫秒，不存 `DATETIME`。** JDBC 驱动会用 JVM 默认时区解释 `DATETIME`，
于是同一行在不同时区的机器上读出来差几小时——而这种偏差看起来"只是有点怪"，
在日志里极难发现。代价是不能直接用 MySQL 的日期函数查，要按日期查得另建生成列。

**取号不读 `MAX(seq)`**：并发下两个事务都会读到 5、都想写 6。这里用
`INSERT ... ON DUPLICATE KEY UPDATE next_seq = next_seq + 1` 一条语句完成"有则加一、无则建一"，
再用同一个事务读回——这一行会锁到提交为止，于是**同一会话的取号天然串行，不同会话互不影响**。

### 人工确认（U12 + U13）

**默认就是 `ask`。** 一个能执行 shell 的服务，"先问"才是诚实的默认值——
之前 `HitlDecision.Always` 被当 `Once` 处理、也没有任何交互通道，
等于"工具需批准"**实际总是批准**，安全语义是空的。现在四条路径各自落地：

```bash
# 起服务时不设就是 ask（默认）；本地想跳过就 APLAT_HITL=allow
export APLAT_HITL_TIMEOUT_SEC=120
./mvnw -q compile exec:java@serve

# 另一个终端里看谁在等你
curl -s -H 'X-API-Key: dev-secret' 'http://127.0.0.1:8787/hitl/pending?sessionId=s1'
curl -s -X POST -H 'X-API-Key: dev-secret' -H 'Content-Type: application/json' \
     -d '{"decision":"once"}' http://127.0.0.1:8787/hitl/h1
```

| 决策 | 后果 | 注意 |
|---|---|---|
| `once` | 只放行这次 | 下一个命令还会再问 |
| `always` | **本会话 × 本工具 × 任意参数** 不再问 | 对 `shell` 而言等于"这个会话里随便跑命令"；不跨会话 |
| `deny` | 不执行，理由回填给模型让它**改道** | 不是"重试"——错误码是 `DENIED` |
| `modify` | 改完再执行 | **后端会把改后的参数重新过一遍策略** |
| （超时） | 按不执行处理，错误码 `TIMEOUT` | 与 `denied` 分开：一个说"人不在"，一个说"人反对" |

**改参必须重过策略**，这条不是形式主义：审批时看到的是 `echo hi`，
如果改参后直接执行，用户（或一个被栽了的前端）就能把命令换成 `rm -rf /`。
真机验过：把命令改成 `rm -rf /` 会拿到 `BLOCKED_BY_POLICY / DESTRUCTIVE_RM`。

**前端面板在中间那栏**（`ui/src/ApprovalPanel.tsx`）：四个按钮 + 一个改参编辑器。
需要批准的工具在执行前会让卡片出现，卡片上有参数、理由和剩余秒数。
状态真相取自 `GET /hitl/pending` 而不是从事件流推导——**超时不产生任何事件**，
只靠事件流那些"等人点"的卡片会永远挂在界面上；顺带还解决了多端同时打开的问题。

### 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `APLAT_LLM_BASE_URL` / `APLAT_LLM_API_KEY` / `APLAT_LLM_MODEL` | 空 | 不设则用离线脚本化模型 |
| `APLAT_HTTP_HOST` | `127.0.0.1` | **默认只听回环**：这个服务能执行 shell |
| `APLAT_HTTP_PORT` | `8787` | `0` = 系统分配 |
| `APLAT_API_KEY` | 空 | 设了就强制校验；`/health` 豁免 |
| `APLAT_SSE_HEARTBEAT_MS` | `15000` | SSE 心跳，防中间层掐连接；**同时决定断连检测延迟**（最多滞后 2 个周期） |
| `APLAT_CORS_ANY_ORIGIN` | `true` | 开发态方便前端；生产应收紧 |
| `APLAT_RATE_LIMIT_PER_MIN` | `120` | 按调用方隔离的每分钟请求上限；`0` = 不限 |
| `APLAT_AUDIT_FILE` | 空 | 设了就落盘为 JSON Lines；不设只留内存（重启即丢） |
| `APLAT_DB_URL` | 空 | **不设 = 内存实现（重启即丢）**；设了走 JDBC（`jdbc:mysql:` 或 `jdbc:h2:…;MODE=MySQL`） |
| `APLAT_DB_USER` | `root` | 数据库用户 |
| `APLAT_DB_PASSWORD` | 空 | 数据库口令（用 `APLAT_DB_*` 而不是塞进 URL，避免密码进 shell 历史与进程列表） |
| `APLAT_HITL` | `ask` | `ask` = 停等人批准；`allow` = 不问人直接放行；`deny` = 直接拒绝 |
| `APLAT_HITL_TIMEOUT_SEC` | `120` | 无人应答的等待上限；到点按超时处理（与「拒绝」区分） |

### 在 IntelliJ IDEA 里

直接用 IDEA 打开本目录即可（已含 `.mvn/wrapper`，IDEA 会自动用它作为 Maven，
JDK 选 21）。仓库预置了三个共享运行配置，在右上角运行下拉框里直接选：

| 运行配置 | 用途 |
|---|---|
| `Demo（离线脚本模型）` | 不需要任何环境变量，直接看一次完整 turn |
| `Serve（HTTP+SSE）` | 起服务，浏览器打开 http://127.0.0.1:8787 用调试控制台 |
| `全部测试` | 跑 `com.aplat` 下全部用例 |

要用**真实模型**：先 `cp .env.example .env`（或在 IDEA 的 Run Configuration →
Environment variables 里填），再运行对应配置。

> `.env` 已被 `.gitignore` 忽略。**不要把 API Key 写进 `.run/*.run.xml` 或提交到仓库。**

装好 Docker 后不用改代码：`DockerSandbox.available()` 为真时装配根会自动切到容器沙箱。

---

## 3. 骨架怎么读

```
run/Platform        ← 唯一的装配根。换实现只改这里
run/Serve           ← 起服务（U02 入口）
run/Demo            ← 离线段到端演示
  ├─ kernel/        Ctx / SeamRegistry / EventBus / Kernel
  ├─ seam/          9 条能力缝接口 + DTO（无实现）
  ├─ loop/          AgentLoop（构造注入，无插件壳）
  ├─ hitl/          InteractiveHitl（U12/U13）：真的停下来等人 + 会话级放行表
  ├─ tools/         ToolPipeline（五道判断）+ ToolPolicy（U15）+ 内置工具 + shell 工具
  ├─ sandbox/       CommandPolicy / ProcessSandbox / DockerSandbox
  ├─ context/       BudgetContextProvider（三层压缩 + 双记录）
  ├─ llm/           OpenAiCompatibleAdapter（流式 SSE）/ ScriptedLlmAdapter（测试）
  ├─ session/       EventSourcedSessionLog / AgUiMapper（信封）/ AgUiProjector（AG-UI 规范）
  ├─ web/           HttpTransport / SseWriter / EventPump / ServerConfig / UiAssets
  │                 ApiKeyGuard（U16）/ RequestScope · AuditLog（U17）/ RateLimiter（U17）
  └─ store/         JdbcStore（MySQL/H2 一套 SQL）+ StoreFactory（由环境变量选）+ InMemoryStore
ui/                 ← 前端（U07a/U07b）：Vite + React + CopilotKit，产物由 /ui/ 托管
  ├─ src/App.tsx        聊天面装配：直连 HttpAgent + 工具卡片渲染器 + 时间线
  ├─ src/ToolCard.tsx   工具调用卡片（参数 / 结果 / 被拒标红）
  └─ src/Timeline.tsx   过程时间线（订阅 /agui/events?raw=1）
```

### 三条贯穿全码的规则

1. **模型可见的必已记录**：任何进上下文的东西先落 `SessionEvent`。所以出问题能靠
   `sessionLog.replay(sessionId)` 复现当时模型看到的全部输入。
2. **失败不穿透**：工具异常、未知工具、参数非法、沙箱超时，全部转成带 `errorCode` 的
   `ToolResult` 回填给模型。异常穿透会杀掉整个 turn，而模型本来能自己换个做法。
3. **压缩不静默**：`PreparedContext.dropped` 逐条记录被丢的块与原因。线上"模型怎么忘了"
   的判断依据只有这份记录。

---

## 4. 测试即验收

| 测试类 | 对应验收（实施计划 §9.2 DoD） |
|---|---|
| `SeamRegistryTest` | 可替换组件：绑定 / 取用 / **单点替换** |
| `EventBusTest` | 订阅可回收（"卸载即回收"的全部含义） |
| `SessionLogTest` | 会话日志 append-only + 增量续传 + **AG-UI 映射** |
| `ToolPipelineTest` | tools：幻觉防护 / 参数校验 / 策略拦截 / HITL 四决策 / 异常兜底 |
| `SandboxTest` | sandbox：执行 / 超时 / 退出码 / Docker 不可用降级 / 危险命令拦截 |
| `BudgetContextProviderTest` | context：预算裁剪 / 分层压缩 / **双记录可归因** |
| `AgentLoopTest` | **U04 验收**：3 步任务完成 · 编造工具名能自纠 · 预算终止 · 拒绝后改道 · 异常收口 |
| `StoreContract`（抽象契约） | store：seq 单调 / 增量 / 快照往返（含 null 字段）/ 幂等键 / **并发写不丢不重** |
| `InMemoryStoreTest` | 内存实现跑上面那套契约（它是**参照物**，证明契约本身可满足） |
| `JdbcStoreTest` | JDBC 实现跑**同一套契约**（H2 MySQL 模式）+ **重启可恢复** + H2 缺 `MODE=MySQL` 时启动报错 |
| `MySqlStoreTest` | **同一套契约跑在真 MySQL 上**（默认跳过，见下方跑法）——"可替换"最硬的证据 |
| `SseWriterTest` | **U02**：帧格式 / data 单行 / **无名帧**（不写 `event:`，否则 `onmessage` 收不到）/ 写失败唤醒等待者 |
| `ApiKeyGuardTest` | **U16 前置**：三种携带方式 / 错误 key 全拒 |
| `EventPumpTest` | **U09**：回填与实时的重叠不重推、竞态窗口不漏事件 |
| `HttpTransportTest` | **U02 验收**：一句话进去逐 token 出来 · 事件序列固定 · 参数校验 · 401 |
| `EventStreamResumeTest` | **U09 验收**：任意游标续传 = 全量序列的后续段 · `raw=1` 全量可达 · 断连后订阅被回收 |
| `UiAssetsTest` | **U07a 前置**：路径穿越（含 `%2e%2e`）被挡 · SPA 回退 · MIME 正确 |
| `ToolPolicyTest` | **U15 验收**：不靠名字拦截 · 声明哪个字段就查哪个 · 越权工具被拒 · 漏声明被 lint |
| `AgUiProjectorTest` | **U08 验收**：AG-UI 规范字段 · step 生命周期配平 · 内部事件不外泄 |
| `AgUiRunEndpointTest` | **U07a 验收**：`POST /agui/run` 事件序列 · 多轮历史喂给循环 · content 分片 |
| `RateLimiterTest` | **U17**：额度用满即拒 · 按时间连续补充 · 不超补 · `Retry-After` 向上取整 · 按调用方隔离 |
| `AuditLogTest` | **U17**：可追溯 · 内存有界 · 落盘 JSONL · **close 不丢记录** · **绝不记密钥原文** |
| `AuthAuditRateLimitTest` | **U16/U17 验收**：401 / 免鉴权白名单 / 429+Retry-After / 审计指纹 / 流式请求记成 200 |
| `InteractiveHitlTest` | **U12 验收**：五条路径 · always 只在本会话生效 · 超时与回答撞车只有一个赢家 · 不接受伪造的 timeout |
| `HitlEndpointTest` | **U12/U13 验收**：run 挂住等人 → 队列可见 → 回答后继续跑并**真的执行了** · 409 / 400 / 501 / 401 |
| （`ToolPipelineTest` 4d/4e） | **U13 加固**：改参后**重新过策略**（否则"人改参"就是个后门）· 改参必须是合法 JSON 对象 |

`StoreContract` 的用法是有意的：**不为新实现另写一套测试**，让它跟内存实现跑同一组断言。
这才是"可替换"的证明方式——不是"我写了个 MySQL 实现"，而是"两个实现在同一份契约下都通过"。

真 MySQL 的验收默认跳过（`mvn test` 不该要求本机有库），要跑就显式指向一个**专用测试库**
（它会清表）：

```bash
mysql -u root -e 'CREATE DATABASE aplat_test CHARACTER SET utf8mb4'
APLAT_IT_DB_URL='jdbc:mysql://127.0.0.1:3306/aplat_test' APLAT_IT_DB_USER=root \
  ./mvnw test -Dtest=MySqlStoreTest -DfailIfNoSpecifiedTests=false
```

---

## 5. 已知边界（不要误会它已经完整）

- **`ProcessSandbox` 不是安全边界**：与宿主同权限，只用于没有 Docker 的开发环境。生产必须切
  容器沙箱。
- **不设 `APLAT_DB_URL` 时 Store 是内存的，重启即丢**：现在有 `store.jdbc[mysql]` 可选了，
  但**默认仍然是不持久化**（为了让 `mvn test` 与离线演示零依赖）。启动横幅与 `/health`
  都会把当前用的是哪种标出来——排查"数据为什么丢了"第一件事就是看那里。
- **审计日志、限流计数、HITL 放行表还没进数据库**：它们各自在内存里（审计可选落 JSON Lines 文件）。
  该不该塞进 `Store` 是个设计问题而不是顺手的事——审计是 append-only 的**文本流**，
  与"事件溯源"的语义不同，直接复用 `aplat_events` 会把两件事混成一件。
  多实例共享要的是"计数/放行表"的共享存储，那更接近 U26 的议题。
- **token 估算用 chars/4 启发式**：够触发裁剪，不用于计费。真实用量取 provider 的 `usage`。
- **`McpClient` / `Durable` 只有接口**：阶段二实现。现在装配不绑它们，`ctx.optional()` 取用时
  返回 `empty` 而不是抛异常——这就是"缺失即降级"。
- **没有 Spring**：内核刻意保持零框架依赖，换成 Spring 时 `Platform` 退化为一份
  `@Configuration`、`Ctx` 退化为容器门面，业务代码不动。
- **传输层是单实例内存态**：`EventPump` 的直接订阅只在同一进程内有效。多实例部署需要
  事件外投（Redis Streams / MQ）——那属于 U26 之后的事，现在刻不做。
- **SSE 断连检测滞后 ≤ 2 × 心跳周期**：对端断开的第一次写会成功（TCP 已收到 FIN，但本地仍可写），
  第二次才 EPIPE。默认 15s 心跳意味着订阅最多滞留约 30s 才回收——要更快就调小该值。
- **SSE 没有背压**：慢客户端会拖住它自己那条虚拟线程（不会拖垮别人，但也不会自动丢帧）。
  单机自查够用；对外服务前需要加"落后太多就断开"的策略。
- **`ApiKeyGuard` 还不是鉴权体系**：只有"一个共享 key 对不对"，**没有 RBAC、没有密钥轮转、没有按用户授权**。
  身份只是密钥指纹——够审计溯源，不够做权限分级。完整版是 U25。
- **审计默认只留内存**：设了 `APLAT_AUDIT_FILE` 才落盘，且是本地文件（可删改）。
  真要做到"不可否认"，得进 WORM 存储或远端日志——方向在这里，但不在本项目范围内。
- **限流是单实例的**：每个进程各限各的。多实例部署时需要共享计数（Redis 之类）。
- **限流不区分接口权重**：一条 SSE 长连接和一次 `/health` 同样算一个令牌。
- **审计里的 IP 在反向代理后面是代理地址**：要还原真实来源得解析 `X-Forwarded-For`，
  而那需要先确定信任边界——现在刻意没做，免得给出一个看着对其实可伪造的值。
- **前端直连只适合本地**：`agents__unsafe_dev_only` 是官方给开发用的口子，生产要走
  `selfManagedAgents`（付费档）或架 runtime 代理。详见「前端（U07a）」一节的警告。
- **工具调用的可视化只覆盖"已知工具"**：`GET /tools` 是**启动时**拉一次的，运行中动态注册的
  （未来的 MCP 工具）不会自动出现在界面上，需要刷新页面。属可接受的当前限制。
- **过程时间线是"全量、无过滤"的**：它把内部事件也摊在界面上，所以**别对公网开放 `/ui/`** ——
  里面有压缩决策、上下文规模这类内部信息。
- **`always` 的粒度是「会话 × 工具 × 任意参数」**：点一次「本会话总是允许」，对 `shell` 而言就是
  该会话内不再拦截任何命令。这是它好用的原因也是它的风险，所以粒度写在了按钮的 title 里。
  更细的粒度（按命令模式放行）得等权限体系（U25）。
- **等待确认期间断连不会中止 turn**：SSE 客户端断开后循环仍在跑，那次确认会一路等到超时。
  这是刻意的——断线多半是网络抖动，直接取消一个正在进行的任务更糟；
  但也意味着「关掉浏览器」不等于「取消这次待批操作」（要取消就去提交一个 `deny`）。
- **放行表与待确认队列都只在内存里**：重启即清空，多实例之间也不共享
  （在 A 实例点了「总是允许」，B 实例还会问）。与限流同一个问题：**Store 已经能持久化了，
  但这三样还没接进去**（见上一条边界）。
- **前端面板按会话过滤**：只显示当前 thread 的待确认。想看全部会话的待办，
  用 `GET /hitl/pending`（不带 `sessionId`）。
- **两种事件方言并存**（规范 AG-UI 与本平台信封）：这是刻意的，但有认知成本。
  `/agui/stream` + 内置控制台是 U02 时期的东西，等自研面（U07b）成熟后应当收掉一种。

---

## 6. 下一步（按依赖顺序）

1. **U19/U20 耐久**：实现 `Durable`，`resume()` 用最近快照 + 其后事件重建。
   现在 `AgentLoop` 只写 `state.snapshot` 事件、并不**消费** `Store` 的快照接口，
   所以崩溃后**不会真的续跑**——地基（`aplat_snapshots` 表 + `latestSnapshot()`）已经在了，
   缺的是"谁来读它、从哪一步接上"。这是 U18 之后最该做的一件。
2. **把审计接进 Store**：审计现在是"可选落 JSON Lines 文件"，进程崩了会丢最后几条。
   接进来之后"谁在什么时候调了什么"才真正跨重启可用。注意别直接复用 `aplat_events`（见边界那节）。
3. **U24 MCP**：工具会在运行中动态出现，届时要让 `/tools` 的变更能推给前端（当前是启动时拉一次），
   并让 MCP 工具也能声明 `commandField`，从而自动落进策略与确认门控。
4. **U25 RBAC + 密钥管理**：把「一个共享 key」升级成按用户/角色的授权与轮转；
   HITL 的放行表也该跟着变成「按角色可放行哪些工具」。
5. **连接池（HikariCP）**：现在每次操作开一条连接，本地够用；`JdbcStore.Connections`
   这个 supplier 就是为此留的缝。换池之前别急着上多实例——单实例的瓶颈先量一下再动手。
6. **U22 调度**：定时任务若碰到需要批准的工具，要决定是「提前批准」还是「到点没人就跳过」——
   这是 HITL 与调度交叉处唯一需要新设计的地方。

每一项都能独立开发、独立测试、独立交付——这正是需求单元化的目的。
