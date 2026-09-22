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
| **断线续传（U09）** | `web/EventPump` | ✅ 机制已验（无缺无重） |
| **API Key 守卫（U16 前置）** | `web/ApiKeyGuard` | ✅ 最小闸，完整鉴权仍属 U16 |

**尚未做**（按计划属后续需求单元）：MySQL Store（U18）、MCP 接入（U24）、耐久状态机（U19/U20）、
RBAC（U25）、CopilotKit 前端（U07a/b）、可观测台（U23）。

---

## 2. 跑起来

前置：JDK 21+。**不需要预装 Maven** —— 仓库自带 wrapper（`mvnw`），首次运行会自动下载 Maven 3.9.16。

```bash
# 离线演示：脚本化模型 + 进程沙箱。不需要网络、不需要 Docker
./mvnw -q compile exec:java@demo

# 起 HTTP 服务（U02）：默认 http://127.0.0.1:8787
./mvnw -q compile exec:java@serve

# 单元测试
./mvnw test
```

> `exec:java@serve` 这个写法是必须的：POM 的 `<configuration>` 里显式写了 `mainClass`，
> 此时 `-Dexec.mainClass=` **会被忽略**（配置优先级高于用户属性），
> 所以两个入口做成了两个 execution id（`demo` / `serve`）。裸跑 `exec:java` 默认走 `demo`。

### 接口面

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/` | 内置调试控制台（**不是 U07 的正式前端**，只用来肉眼确认链路） |
| `GET` | `/health` | 健康 + 装配自检（免鉴权，供探针） |
| `GET` | `/kernel` | 装配清单与可替换能力缝（排查第一站） |
| `POST` | `/run` | `{"sessionId?","input"}` → 跑完一个 turn，返回 JSON |
| `GET` | `/agui/stream?input=&sessionId=` | 跑一个 turn 并 SSE 流式吐 AG-UI 事件 |
| `GET` | `/agui/events/{sessionId}?lastEventId=` | 纯事件面：回填 + 实时，**断线续传** |
| `GET` | `/sessions/{sessionId}/events` | 回放为 JSON（离线排查） |

```bash
# 同步跑一次
curl -s -X POST http://127.0.0.1:8787/run \
     -H 'Content-Type: application/json' \
     -d '{"input":"用 shell 打印当前目录"}'

# 流式（逐 token）
curl -N 'http://127.0.0.1:8787/agui/stream?input=hello'

# 续传：从第 7 条之后补齐，看到 turn.closed 就收摊
curl -N 'http://127.0.0.1:8787/agui/events/s-abc?lastEventId=7&once=turn.closed'
```

**事件流与 turn 执行是两条独立入口**——`/agui/stream` 只是"订阅 + 触发一次 turn"的组合，
`/agui/events` 完全不碰循环。所以断线重连、多端同看、事后补看走的是同一条路，后端不需要做特例。

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
  ├─ kernel/        Ctx / SeamRegistry / EventBus / Kernel
  ├─ seam/          9 条能力缝接口 + DTO（无实现）
  ├─ loop/          AgentLoop（构造注入，无插件壳）
  ├─ tools/         ToolPipeline（五道判断）+ 内置工具 + shell 工具
  ├─ sandbox/       CommandPolicy / ProcessSandbox / DockerSandbox
  ├─ context/       BudgetContextProvider（三层压缩 + 双记录）
  ├─ llm/           OpenAiCompatibleAdapter（流式 SSE）/ ScriptedLlmAdapter（测试）
  ├─ session/       EventSourcedSessionLog / AgUiMapper
  ├─ web/           HttpTransport / SseWriter / EventPump / ApiKeyGuard / ServerConfig
  └─ store/         InMemoryStore（Store 契约的参照实现）
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

---

## 6. 下一步（按依赖顺序）

1. **U15 危险命令拦截 + 工具级权限补全**：现在 `CommandPolicy` 只认名字叫 `shell` 的工具，
   新加的执行类工具不会被拦——应把策略挂到 `Tool` 上（或做成第 10 条缝）。
2. **U12/U13 HITL 四条路径**：`HitlDecision.Always` 目前按 `Once` 处理，需要会话级放行表；
   传输层已能承载（HITL 会阻塞 handler 线程，正是长连接的用法）。
3. **U07a 接入 CopilotKit**：`runtimeUrl` 指向 `/agui/stream` 那套端点即可，后端零改动。
4. **U18 MySQL Store**：实现 `Store`，继承 `StoreContractTest`。
5. **U19/U20 耐久**：实现 `Durable`，`resume()` 用最近快照 + 其后事件重建。

每一项都能独立开发、独立测试、独立交付——这正是需求单元化的目的。
