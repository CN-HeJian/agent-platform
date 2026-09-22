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
| 内存 Store（MySQL 的契约参照） | `store/InMemoryStore` | ✅ |
| 装配根 | `run/Platform` | ✅ |
| **HTTP + SSE 传输层（U02）** | `web/` | ✅ 零新增依赖（JDK HttpServer + 虚拟线程） |
| **AG-UI 规范端点（U08）** | `session/AgUiProjector` + `POST /agui/run` | ✅ 事件形状符合规范，客户端可直连 |
| **断线续传（U09）** | `web/EventPump` | ✅ 机制已验（无缺无重） |
| **API Key 守卫（U16 前置）** | `web/ApiKeyGuard` | ✅ 最小闸，完整鉴权仍属 U16 |
| **工具策略（U15）** | `tools/ToolPolicy` + `ToolSpec.executing` | ✅ 工具级权限 + 危险命令，**不靠工具名** |
| **前端聊天面（U07a）** | `ui/`（Vite + React + CopilotKit） | ✅ 直连 `/agui/run`，已用真浏览器验过 |

**尚未做**（按计划属后续需求单元）：MySQL Store（U18）、MCP 接入（U24）、耐久状态机（U19/U20）、
RBAC（U25）、过程时间线与工具卡片渲染（U07b）、可观测台（U23）。

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

# 单元测试
./mvnw test
```

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
| `POST` | `/run` | `{"sessionId?","input"}` → 跑完一个 turn，返回 JSON |
| `GET` | `/agui/stream?input=&sessionId=` | 简化流：跑一个 turn 并 SSE 吐**本平台信封**事件（自带控制台用） |
| `GET` | `/agui/events/{sessionId}?lastEventId=` | 纯事件面：回填 + 实时，**断线续传** |
| `GET` | `/sessions/{sessionId}/events` | 回放为 JSON（离线排查） |

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
> 另外如果你把 `APLAT_API_KEY` 打开了，前端**目前还不能自动带上**——
> 静态资源免鉴权，但 `/agui/run` 会 401。要么先关掉 key，要么在 provider 里补 headers（属 U16 收尾）。

### 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `APLAT_LLM_BASE_URL` / `APLAT_LLM_API_KEY` / `APLAT_LLM_MODEL` | 空 | 不设则用离线脚本化模型 |
| `APLAT_HTTP_HOST` | `127.0.0.1` | **默认只听回环**：这个服务能执行 shell |
| `APLAT_HTTP_PORT` | `8787` | `0` = 系统分配 |
| `APLAT_API_KEY` | 空 | 设了就强制校验；`/health` 豁免 |
| `APLAT_SSE_HEARTBEAT_MS` | `15000` | SSE 心跳，防中间层掐连接；**同时决定断连检测延迟**（最多滞后 2 个周期） |
| `APLAT_CORS_ANY_ORIGIN` | `true` | 开发态方便前端；生产应收紧 |

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
  ├─ tools/         ToolPipeline（五道判断）+ ToolPolicy（U15）+ 内置工具 + shell 工具
  ├─ sandbox/       CommandPolicy / ProcessSandbox / DockerSandbox
  ├─ context/       BudgetContextProvider（三层压缩 + 双记录）
  ├─ llm/           OpenAiCompatibleAdapter（流式 SSE）/ ScriptedLlmAdapter（测试）
  ├─ session/       EventSourcedSessionLog / AgUiMapper（信封）/ AgUiProjector（AG-UI 规范）
  ├─ web/           HttpTransport / SseWriter / EventPump / ApiKeyGuard / ServerConfig
  │                 UiAssets（托管 ui/dist：路径穿越防护 + SPA 回退）
  └─ store/         InMemoryStore（Store 契约的参照实现）
ui/                 ← 前端（U07a）：Vite + React + CopilotKit，产物由 /ui/ 托管
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
| `StoreContractTest` | store：seq 单调 / 增量 / 快照 / 幂等键 —— **日后 MySQL 实现继承这套断言** |
| `SseWriterTest` | **U02**：帧格式 / data 单行 / 关闭后不写 / 写失败唤醒等待者 |
| `ApiKeyGuardTest` | **U16 前置**：三种携带方式 / 错误 key 全拒 |
| `EventPumpTest` | **U09**：回填与实时的重叠不重推、竞态窗口不漏事件 |
| `HttpTransportTest` | **U02 验收**：一句话进去逐 token 出来 · 事件序列固定 · 参数校验 · 401 |
| `EventStreamResumeTest` | **U09 验收**：任意游标续传 = 全量序列的后续段 · 断连后订阅被回收 |
| `UiAssetsTest` | **U07a 前置**：路径穿越（含 `%2e%2e`）被挡 · SPA 回退 · MIME 正确 |
| `ToolPolicyTest` | **U15 验收**：不靠名字拦截 · 声明哪个字段就查哪个 · 越权工具被拒 · 漏声明被 lint |
| `AgUiProjectorTest` | **U08 验收**：AG-UI 规范字段 · step 生命周期配平 · 内部事件不外泄 |
| `AgUiRunEndpointTest` | **U07a 验收**：`POST /agui/run` 事件序列 · 多轮历史喂给循环 · content 分片 |

`StoreContractTest` 的用法是有意的：写 MySQL 实现时不要另写一套测试，让它跟内存实现
跑同一组断言。这才是"可替换"的证明方式。

---

## 5. 已知边界（不要误会它已经完整）

- **`ProcessSandbox` 不是安全边界**：与宿主同权限，只用于没有 Docker 的开发环境。生产必须切
  容器沙箱。
- **内存 Store 重启即丢**：MySQL 实现是 U18 的事，接口已就位（`store/InMemoryStore` 的
  `id` 是 `store.in-memory`，将来并列一个 `store.mysql`）。
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
- **`ApiKeyGuard` 是安全网不是鉴权体系**：没有 RBAC、没有轮转、没有审计。完整版是 U16/U17/U25。
- **前端直连只适合本地**：`agents__unsafe_dev_only` 是官方给开发用的口子，生产要走
  `selfManagedAgents`（付费档）或架 runtime 代理。详见「前端（U07a）」一节的警告。
- **前端还不能自动带 API Key**：开了 `APLAT_API_KEY` 后 `/ui/` 静态资源仍可访问，
  但 `/agui/run` 会 401。要么先关 key，要么给 provider 补 headers（U16 收尾）。
- **工具调用在界面上还看不见**：AG-UI 事件里 `TOOL_CALL_*` 都发对了（有测试钉住），
  但 CopilotKit 默认不知道该怎么画一个工具调用，需要注册 `renderToolCalls`——
  属 U07b（自研定制）的范围。现在只能看到工具的**文字结果**。
- **两种事件方言并存**（规范 AG-UI 与本平台信封）：这是刻意的，但有认知成本。
  `/agui/stream` + 内置控制台是 U02 时期的东西，等自研面（U07b）成熟后应当收掉一种。

---

## 6. 下一步（按依赖顺序）

1. **U07b 工具卡片 + 过程时间线**：前端的链已经通了，缺的是"工具调用怎么画"。
   在 React 侧注册 `renderToolCalls`，把 `TOOL_CALL_START/ARGS/RESULT` 渲染成可展开的卡片。
2. **U16 收尾**：让前端能带 API Key（provider headers），并把 `/agui/run` 的鉴权打通。
   顺带补审计与限流（U17）。
3. **U12/U13 HITL 四条路径**：`HitlDecision.Always` 目前按 `Once` 处理，需要会话级放行表；
   传输层已能承载（HITL 会阻塞 handler 线程，正是长连接的用法）。
4. **U18 MySQL Store**：实现 `Store`，继承 `StoreContractTest`。
5. **U19/U20 耐久**：实现 `Durable`，`resume()` 用最近快照 + 其后事件重建。

每一项都能独立开发、独立测试、独立交付——这正是需求单元化的目的。
