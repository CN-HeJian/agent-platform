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
