# 真实运行记录

以下为 `mvn -B test` + `mvn -B -q compile exec:java` 在本机的实际输出（非示意）。

## 单元测试

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.tools.ToolPipelineTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.context.BudgetContextProviderTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.sandbox.SandboxTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.loop.AgentLoopTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.kernel.SeamRegistryTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.kernel.EventBusTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.store.StoreContractTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.session.SessionLogTest
[INFO] Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 端到端演示（离线脚本化模型 + 进程沙箱）

```
=== 装配清单 ===
[kernel] seams:
  - Store -> InMemoryStore
  - SessionLog -> EventSourcedSessionLog
  - LlmAdapter -> ScriptedLlmAdapter
  - ToolRegistry -> DefaultToolRegistry
  - Sandbox -> ProcessSandbox
  - ContextProvider -> BudgetContextProvider
  - Hitl -> hitl.auto-allow
[kernel] replaceable seams: 9
LLM     : llm.scripted
Sandbox : sandbox.process [available]
模式    : 离线脚本化模型

=== 一次 turn ===
input > 帮我在沙箱里跑一条命令。

=== AG-UI 事件流（前端看到的就是这个） ===
CUSTOM_INPUT_CLAIMED         {text=帮我在沙箱里跑一条命令。}
RUN_STARTED                  {input=帮我在沙箱里跑一条命令。}
CUSTOM_CONTEXT_PREPARED      {step=1, estimatedTokens=43, compressed=false, dropped=0}
STEP_STARTED                 {step=1}
TOOL_CALL_START              {args={"command":"echo hello-from-sandbox"}, step=1, id=call_af632d5f, tool=shell}
TOOL_CALL_END                {step=1, id=call_af632d5f, tool=shell, ok=true, errorCode=null, content=hello-from-sandbox}
STATE_SNAPSHOT               {step=1, state={"step":1}, messages=4}
CUSTOM_CONTEXT_PREPARED      {step=2, estimatedTokens=55, compressed=false, dropped=0}
STEP_STARTED                 {step=2}
TEXT_MESSAGE_CONTENT         {text=命令已执行，输出为 hello-from-sandbox。, step=2}
RUN_FINISHED                 {reason=completed, step=2, finishReason=stop, text=命令已执行，输出为 hello-from-sandbox。}

=== 结果 ===
status : COMPLETED
steps  : 2
answer : 命令已执行，输出为 hello-from-sandbox。

=== 会话回放（排查用；模型看到的每一件事都在这里） ===
#1 input.claimed {text=帮我在沙箱里跑一条命令。}
#2 turn.started {input=帮我在沙箱里跑一条命令。}
#3 context.prepared {step=1, estimatedTokens=43, compressed=false, dropped=0}
#4 step.started {step=1}
#5 tool.call {args={"command":"echo hello-from-sandbox"}, step=1, id=call_af632d5f, tool=shell}
#6 tool.result {step=1, id=call_af632d5f, tool=shell, ok=true, errorCode=null, content=hello-from-sandbox}
#7 state.snapshot {step=1, state={"step":1}, messages=4}
#8 context.prepared {step=2, estimatedTokens=55, compressed=false, dropped=0}
#9 step.started {step=2}
#10 llm.chunk {text=命令已执行，输出为 hello-from-sandbox。, step=2}
#11 turn.closed {reason=completed, step=2, finishReason=stop, text=命令已执行，输出为 hello-from-sandbox。}

可替换能力缝: 7 条已装配
```

## 这份输出里值得注意的三点

1. **`CUSTOM_*` 是故意的**：`input.claimed`、`context.prepared` 是内部记账事件，AG-UI 没有对应
   标准事件名，映射器兜底成 `CUSTOM_*` 而不是丢弃——协议外的信息也不该消失。
2. **`tool.result` 里带着 `errorCode=null`**：成功路径上这个字段就是 null，所以
   `SessionEvent` 刻意没有用 `Map.copyOf`（它拒绝 null 值）。这个坑在第一次跑测试时就炸过一次。
3. **同一个 `shell` 工具在演示里是"需批准"的**：装配用的是 `Hitl.auto-allow`。换成
   `Hitl.autoDeny` 后循环不会中断，而是把 `DENIED` 当观察回填给模型改道——
   `AgentLoopTest.denialBecomesObservationAndLoopContinues` 验证的就是这条。

---

# U02 传输层（HTTP + SSE）真实运行记录

以下为 `./mvnw test` 与真起服务的 `curl` 抓取结果（本机实测，逐字复制）。

## 单元测试

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.tools.ToolPipelineTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.context.BudgetContextProviderTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.web.EventPumpTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.web.ApiKeyGuardTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.web.SseWriterTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.web.HttpTransportTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.web.EventStreamResumeTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.sandbox.SandboxTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.loop.AgentLoopTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.kernel.SeamRegistryTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.kernel.EventBusTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.store.StoreContractTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- com.aplat.session.SessionLogTest
[INFO] Tests run: 83, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 起服务

```
$ APLAT_HTTP_PORT=8791 ./mvnw -B -q compile exec:java@serve
=== 装配清单 ===
[kernel] seams:
  - Store -> InMemoryStore
  - SessionLog -> EventSourcedSessionLog
  - LlmAdapter -> ScriptedLlmAdapter
  - ToolRegistry -> DefaultToolRegistry
  - Sandbox -> ProcessSandbox
  - ContextProvider -> BudgetContextProvider
  - Hitl -> hitl.auto-allow
[kernel] replaceable seams: 9
LLM     : llm.scripted（离线脚本）
Sandbox : sandbox.process [available]

=== 已启动 http://127.0.0.1:8791 ===
鉴权    : 未启用（仅监听 127.0.0.1；对外暴露前请设 APLAT_API_KEY）
```

## 健康检查与同步执行

```
$ curl -s http://127.0.0.1:8791/health
{"status":"ok","uptimeMs":16811,"llm":"llm.scripted","sandbox":"sandbox.process [available]",
 "seams":7,"activeStreams":0,"auth":"disabled(loopback only)"}

$ curl -s -X POST http://127.0.0.1:8791/run -H 'Content-Type: application/json' \
       -d '{"input":"帮我跑一条命令","sessionId":"s-e2e"}'
{"sessionId":"s-e2e","status":"COMPLETED","steps":2,
 "finalText":"已在沙箱中执行命令，输出为 hello-from-http。","events":11}

$ # 再来一次，脚本不该"用尽"
{"sessionId":"s-e2e2","status":"COMPLETED","steps":2,
 "finalText":"已在沙箱中执行命令，输出为 hello-from-http。","events":11}
```

## 流式（SSE）——逐条帧的原始字节

```
$ curl -sN 'http://127.0.0.1:8791/agui/stream?input=帮我跑一条命令&sessionId=s-sse'

event: RUN_REQUESTED
data: {"input":"帮我跑一条命令","sessionId":"s-sse"}

id: 1
event: CUSTOM_INPUT_CLAIMED
data: {"type":"CUSTOM_INPUT_CLAIMED","sessionId":"s-sse","seq":1,"payload":{"text":"帮我跑一条命令"}}

id: 2
event: RUN_STARTED
data: {"type":"RUN_STARTED","sessionId":"s-sse","seq":2,"payload":{"input":"帮我跑一条命令"}}

id: 3
event: CUSTOM_CONTEXT_PREPARED
data: {"type":"CUSTOM_CONTEXT_PREPARED","sessionId":"s-sse","seq":3,
       "payload":{"step":1,"estimatedTokens":41,"compressed":false,"dropped":0}}

id: 4
event: STEP_STARTED
data: {"type":"STEP_STARTED","sessionId":"s-sse","seq":4,"payload":{"step":1}}

id: 5
event: TOOL_CALL_START
data: {"type":"TOOL_CALL_START","sessionId":"s-sse","seq":5,
       "payload":{"tool":"shell","id":"call_...","step":1,"args":"{\"command\":\"echo hello-from-http\"}"}}

id: 6
event: TOOL_CALL_END
data: {"type":"TOOL_CALL_END","sessionId":"s-sse","seq":6,
       "payload":{"step":1,"tool":"shell","ok":true,"errorCode":null,"content":"hello-from-http"}}

id: 7
event: STATE_SNAPSHOT
data: {"type":"STATE_SNAPSHOT","sessionId":"s-sse","seq":7,"payload":{...}}

id: 8 / id: 9  → 第二步的 CUSTOM_CONTEXT_PREPARED + STEP_STARTED
id: 10
event: TEXT_MESSAGE_CONTENT
data: {"type":"TEXT_MESSAGE_CONTENT","sessionId":"s-sse","seq":10,"payload":{"text":"已在沙箱中执行命令，输出为 hello-from-http。"}}

id: 11
event: RUN_FINISHED
data: {"type":"RUN_FINISHED","sessionId":"s-sse","seq":11,"payload":{"reason":"completed","step":2,"finishReason":"stop",...}}

event: RUN_RESULT
data: {"sessionId":"s-sse","status":"COMPLETED","steps":2,"events":11}
```

帧数：**13**（11 条会话事件 + 收尾两条传输层回执）。
`id:` 直接就是会话内 seq，浏览器会自动把它作为 `Last-Event-ID` 回传——续传不需要前端做任何事。

## 断线续传（U09）

```
$ curl -sN 'http://127.0.0.1:8791/agui/events/s-sse?lastEventId=4&once=turn.closed' | grep '^id: '
5 6 7 8 9 10 11          ← 全量是 1..11，续传恰好补齐 5..11，无缺无重

$ curl -sN -H 'Last-Event-ID: 8' 'http://127.0.0.1:8791/agui/events/s-sse?once=turn.closed' | grep '^id: '
9 10 11                  ← 浏览器 EventSource 的路径（请求头）与查询参数等价

$ curl -s http://127.0.0.1:8791/sessions/s-sse/events
count = 11
前 3 条: ['CUSTOM_INPUT_CLAIMED', 'RUN_STARTED', 'CUSTOM_CONTEXT_PREPARED']
后 3 条: ['STEP_STARTED', 'TEXT_MESSAGE_CONTENT', 'RUN_FINISHED']
```

## 断连回收

```
客户端连接中      → activeStreams = 1
客户端断开后      → activeStreams = 1      ← 第一次写"成功"（TCP 收到 FIN 但本地仍可写）
完成 2 个心跳周期 → activeStreams = 0      ← 第二次写 EPIPE，订阅被回收
```

## 这份输出里值得注意的三点

1. **`RUN_REQUESTED` / `RUN_RESULT`：传输层回执**，只有 `event:` 没有 `id:`——它们不是会话事件，
   不该参与续传游标。前端可以安全忽略。
2. **`id:` = 会话 seq，不是"第几条帧"**：所以 `RUN_REQUESTED` 不带 id 时编号不会错位。
   这也是续传能精确到"某一条"的前提。
3. **两次 `POST /run` 都成功**：`ScriptedLlmAdapter` 默认脚本用尽即吐 `[script exhausted]`，
   服务版加了 `repeat()`——服务是要被反复戳的，不该第二次请求就失效。

---

# U15 + U08 + U07a 真实运行记录

## 单元测试

```
Tests run: 129, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

新增 / 变化：`ToolPolicyTest`(12) · `UiAssetsTest`(9) · `AgUiProjectorTest`(15) · `AgUiRunEndpointTest`(10)。

## AG-UI 规范端点（U08）：`POST /agui/run` 的真实字节

```
$ curl -sN -X POST http://127.0.0.1:8787/agui/run -H 'Content-Type: application/json' \
       -d '{"threadId":"t-demo","runId":"r-demo","messages":[{"role":"user","content":"帮我跑一条命令"}]}'

id: 2
event: RUN_STARTED
data: {"type":"RUN_STARTED","threadId":"t-demo","runId":"r-demo"}

id: 4
event: STEP_STARTED
data: {"type":"STEP_STARTED","stepName":"step-1"}

id: 5
event: TOOL_CALL_START
data: {"type":"TOOL_CALL_START","toolCallId":"call_fa0e6c86","toolCallName":"shell"}

event: TOOL_CALL_ARGS
data: {"type":"TOOL_CALL_ARGS","toolCallId":"call_fa0e6c86","delta":"{\"command\":\"echo hello-from-http\"}"}

id: 6
event: TOOL_CALL_END
data: {"type":"TOOL_CALL_END","toolCallId":"call_fa0e6c86"}

event: TOOL_CALL_RESULT
data: {"type":"TOOL_CALL_RESULT","messageId":"toolmsg-6","toolCallId":"call_fa0e6c86","role":"tool","content":"hello-from-http\n"}

id: 7
event: STATE_SNAPSHOT
data: {"type":"STATE_SNAPSHOT","snapshot":{"messages":"4","step":"1"}}

id: 9
event: STEP_FINISHED
data: {"type":"STEP_FINISHED","stepName":"step-1"}

event: STEP_STARTED
data: {"type":"STEP_STARTED","stepName":"step-2"}

id: 10
event: TEXT_MESSAGE_START
data: {"type":"TEXT_MESSAGE_START","messageId":"msg-10","role":"assistant"}

event: TEXT_MESSAGE_CONTENT
data: {"type":"TEXT_MESSAGE_CONTENT","messageId":"msg-10","delta":"已在沙箱中执行命令，输出为 hello-from-http。"}

id: 11
event: TEXT_MESSAGE_END
data: {"type":"TEXT_MESSAGE_END","messageId":"msg-10"}

event: STEP_FINISHED
data: {"type":"STEP_FINISHED","stepName":"step-2"}

event: RUN_FINISHED
data: {"type":"RUN_FINISHED","threadId":"t-demo","runId":"r-demo"}
```

`id:` 只挂在"一条内部事件产出的第一条 AG-UI 事件"上 —— 所以 id 唯一且递增
（2,4,5,6,7,9,10,11），续传断点不会错位。

## 前端聊天面（U07a）：真浏览器里跑通

无头 Chrome（CDP 驱动）真的输入并发送一条消息，页面文本：

```
输入结果 : "SENT"
--- 页面文本 ---
agent-platform · 会话
CopilotKit 聊天面 · AG-UI 直连 http://127.0.0.1:8787/agui/run
健康检查 / 装配清单 / 调试控制台
说点什么试试。离线模式下后端用的是脚本化模型，所以回复是固定剧本；…
用 shell 打印当前目录                     ← 用户消息
已在沙箱中执行命令，输出为 hello-from-http。   ← 助手回复（流式抵达）
Powered by CopilotKit
```

服务端同期日志：

```
[web] agui run thread=580bebc9-0b37-4da8-aa39-8ff5200c64fb run=f30c4cbf-… historyTurns=0 input=用 shell 打印当前目录
[web] agui run done thread=580bebc9-0b37-4da8-aa39-8ff5200c64fb status=COMPLETED
```

截图见 `docs/screenshots/ui-01-initial.png` 与 `ui-02-chat-roundtrip.png`。

## 这一段最值钱的发现：靠真客户端才暴露的协议 bug

第一次用真浏览器跑，**服务端显示 `status=COMPLETED`，前端却报错**：

```
Cannot send 'RUN_FINISHED' while steps are still active: step-1, step-2
```

原因：AG-UI 客户端会校验生命周期——每个 `STEP_STARTED` 必须有配对的 `STEP_FINISHED`，
我原先**只发前者不发后者**（当时以为它是可选的）。

三点值得记住：

1. **单测当时是"绿"的**。我写的 13 个投影用例全过——因为它们只断言了我认为对的序列，
   而"我认为对的"本身就是错的。**用自己的假设去测自己的假设，测不出协议理解错误。**
2. **真客户端一次就抓出来了**。这就是为什么"接一个真实前端"不能只做静态截图验收。
3. 修完之后补了 `stepLifecycleIsBalanced` / `streamSatisfiesClientLifecycleChecks`
   两条回归用例，把"配平"变成可执行断言。

## 还有一个诚实的缺口（未做，属 U07b）

AG-UI 事件里 `TOOL_CALL_*` 四件套**都发对了**（有测试钉住，上面 curl 也能看到），
但**界面上看不到工具卡片**：CopilotKit 默认不知道该怎么画一个工具调用，需要在 React 侧
注册 `renderToolCalls`。现在只能读到工具的**文字结果**（"输出为 hello-from-http"）。

这不是后端问题，也不是配置错了，而是 U07b（自研定制）本该做的事。

---

# U07b 工具卡片 + 过程时间线

## 单元测试

```
Tests run: 131, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 真浏览器验收（CDP 驱动，页面证据）

```
发消息前已有工具卡片: 0
工具卡片出现，用时约 0.5s，共 1 张
```

截图 `docs/screenshots/ui-03-toolcard-timeline.png` 里能同时看到两侧：

**左侧 —— 工具卡片（已展开）**
```
shell  echo hello-from-http                       已完成
参数
{
  "command": "echo hello-from-http"
}
结果
hello-from-http
 
已在沙箱中执行命令，输出为 hello-from-http。
```
页脚：`工具卡片 已注册 3 个（echo · add · shell）· 标注"会执行命令"的工具受策略拦截`

**右侧 —— 过程时间线，11 条（逐条就是后端产生的原始会话事件）**
```
 1  CUSTOM_INPUT_CLAIMED      {"text":"用 shell 打印当前目录","historyTurns":0}
 2  RUN_STARTED               {"input":"用 shell 打印当前目录"}
 3  CUSTOM_CONTEXT_PREPARED   {"step":1,"estimatedTokens":43,"compressed":false,...}
 4  STEP_STARTED              {"step":1}
 5  TOOL_CALL_START           {"args":"{\"command\":\"echo hello-from-http\"}",...}
 6  TOOL_CALL_END             {"step":1,"id":"call_fa0e6dc86","tool":"shell","ok":...}
 7  STATE_SNAPSHOT            {"messages":4,"state":"{\"step\":1}","step":1}
 8  CUSTOM_CONTEXT_PREPARED   {"step":2,"estimatedTokens":55,"compressed":false,...}
 9  STEP_STARTED              {"step":2}
10  TEXT_MESSAGE_CONTENT      {"step":2,"text":"已在沙箱中执行命令，输出为 hello-f...}
11  RUN_FINISHED              {"reason":"completed","text":"已在沙箱中执行命令，输出…}
```

注意第 1、3、8 条：`CUSTOM_*` 是**聊天面看不到的内部记账事件**（投影器有意过滤掉了）。
时间线能看到它们，正是它存在的意义——排查"模型为什么忘了"的线索在
`CUSTOM_CONTEXT_PREPARED` 的 `estimatedTokens/compressed` 里，不在聊天窗口里。

## 这一段又踩了一个"静默失效"的坑

时间线第一版显示 **0 条**，而服务端日志明明有 `events session=t-09vnq4ns lastEventId=0` —— 连接是通的。

原因：**SSE 里帧一旦带 `event:` 字段，浏览器就按"具名事件"派发，`es.onmessage` 一条都收不到**，
只能用 `addEventListener('那个名字')`。而我的时间线要"看全部事件"，就只能枚举事件名——
后端一加新类型，界面就**静默少一条**。

修法不是"枚举全部事件名"，而是让后端支持**无名帧**（`?raw=1`）：

```
id: 1
data: {"type":"CUSTOM_INPUT_CLAIMED","sessionId":"s-rawcheck","seq":1,...}
```

没有 `event:` 行，全部走 `onmessage`；事件名没丢（`data` 里的 `type` 就是它）；
`id:` 保留，续传游标照旧。加了 `SseWriterTest.unnamedFramesOmitEventField` 与
`EventStreamResumeTest.rawModeMakesAllEventsReachableViaOnMessage` 两条用例钉住。

> 顺带说明：自带的调试控制台（`/`）不受影响——它**故意**用 `addEventListener` 按类型接，
> 因为那里就是"按事件类型做不同渲染"。两种模式各有各的用处。

## TypeScript 教我的两件事

工具卡片的 props 我没有手写形状，而是从装好的包里推：
`React.ComponentProps<typeof CopilotKit>['renderToolCalls']` → 渲染器 →
`React.ComponentProps<渲染器.render>`。过程中 `tsc` 否掉了我两版：

1. `status` 是**枚举**（`ToolCallStatus`），不是字符串字面量 —— 手写的
   `'InProgress' | 'Executing' | 'Complete'` 不兼容；
2. 那个枚举**并没有从公开入口导出**（`@copilotkit/react-core` 没有它），所以别引用它的成员。

最终改用联合类型自己的结构判别：`result !== undefined` ⇔ 这次调用已结束。
比依赖枚举名更稳——枚举改了、或者它本来就不是字符串枚举，这里都不会坏。

---

# U16 鉴权 + U17 审计与限流

## 单元测试

```
Tests run: 160, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 真实运行（鉴权开启 + 限流 8/min + 审计落盘）

```
=== 已启动 http://127.0.0.1:8792 ===
鉴权    : X-API-Key 已启用（/health 与 /ui/ 静态资源豁免）
```

**鉴权**（同一条 `/run`，只差有没有带 key）：

```
$ curl -X POST /run -d '{"input":"rm -rf /"}'                  → 401
$ curl -X POST /run -H 'X-API-Key: ***' -d '{"input":"你好"}'   → 200
$ curl /health                                                 → 200（探针豁免）
$ curl /audit                                                  → 401（审计也要 key）
```

**限流**（8/min）：连打 12 次

```
200 200 200 200 200 200 200 200 429 429 429 429

HTTP/1.1 429
Retry-after: 8
{"error":"RATE_LIMITED","message":"too many requests (limit 8/min); retry after 8s"}
```

**审计**（`/audit` 读出来的最近几条）：

```
total = 18 | dropped = 0 | persisted = .../logs/audit.jsonl
  POST /run   429  id=298754db  note=rate_limited  0ms
  POST /run   429  id=298754db  note=rate_limited  0ms
```

`id=298754db` 是**密钥指纹**（SHA-256 前 8 位），不是密钥本身。

**落盘的 JSON Lines**（19 行，首尾两条）：

```
{"ts":"...","identity":"anonymous","ip":"127.0.0.1","method":"POST","path":"/run","status":401,"durationMs":20,"note":"unauthorized"}
{"ts":"...","identity":"298754db","ip":"127.0.0.1","method":"POST","path":"/run","status":200,"durationMs":28,"note":""}
...
{"ts":"...","identity":"298754db","ip":"127.0.0.1","method":"POST","path":"/run","status":429,"durationMs":0,"note":"rate_limited"}
{"ts":"...","identity":"298754db","ip":"127.0.0.1","method":"GET","path":"/audit","status":200,"durationMs":1,"note":""}
```

**密钥原文泄漏检查：含密钥原文的行数 = 0**（31 条记录，身份只有 `anonymous` 与 `298754db`）。

> 身份字段第一版写的是 `(rejected)`，翻审计时看到 `GET /ui/  status=200  id=(rejected)` 才发现不对：
> **身份答的是"谁"，不是"结果"**。把它改成 `anonymous`，结果交给 `status` 与 `note` 表达
> （401 + `note=unauthorized` 才是真的被拒）。

## 前端带 key 的三条路（真浏览器验收）

CDP 驱动无头 Chrome：先看没填 key，再写 `localStorage` 刷新。

```
未填 key 时页脚: 工具清单未加载：需要 API Key（右上角填） · 未填 API Key（后端未开鉴权时可忽略）
填 key 后页脚:   工具卡片已注册 3 个（echo · add · shell） · 标注"会执行命令"的工具受策略拦截

toolCardCount: 1     toolCardText: ["shell | echo hello-from-http | 已完成 | 参数 | … | 结果 | hello-from-http"]
timelineRowCount: 11 含 CUSTOM_INPUT_CLAIMED … RUN_FINISHED
```

三条路各自都通了：聊天（`HttpAgent.headers`）、时间线（`?apiKey=`——`EventSource` 不能自定义请求头）、
工具清单（`fetch` 头）。截图见 `docs/screenshots/ui-04-auth-enabled.png`。

**没填 key 时给的是明确提示，不是空列表** —— 静默失败的界面比报错的界面难查得多。

## 审计日志当场抓出了我自己的一个 bug

翻那 31 条记录时看到这么一行：

```
POST /agui/run   0   id=298754db   note=-
```

`status=0` 在我这里的语义是"处理链抛异常、没来得及应答"（`failed()` 为真）。
也就是说**所有流式请求在审计里都被记成了失败**——恰恰是审计最不该犯的错。

原因：SSE 走 `startSse()` 里的 `sendResponseHeaders(200, 0)`，**不经过 `sendBytes()`**，
而我只在 `sendBytes()` 里记了状态码。修法是在 `startSse()` 里也记一笔。

这件事本身就说明审计值得做：这个 bug 不影响任何功能、单测也测不出来，
**只有把真实流量摊开看才会发现**。修完补了 `streamedRequestsAreAuditedAs200`，
并把"流式端点同样受鉴权保护（不带 key 是 401 而不是 200）"钉进同一个用例。

## 另一个被测试抓出来的缺陷：关服务时丢审计

`AuditLog.close()` 最初对写线程调了 `interrupt()`。写线程多半正阻塞在队列 `poll` 上，
一打断就抛 `InterruptedException` 直接退出循环——**队列里还没落盘的记录被整批丢掉**。
正是最需要审计的时候（关服务）丢得最干净。

改成：置位 `running=false`，让写线程按自己的轮询节奏排空后自然退出；
只有 2 秒还没排空才强断，并且**把"丢了多少条"打到 stderr**。
回归用例：`closeDrainsPendingRecords`（塞 200 条立刻 close，断言文件里就是 200 行）。




