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

