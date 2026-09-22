package com.aplat.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aplat.seam.Hitl;
import com.aplat.seam.HitlDecision;
import com.aplat.seam.Tool;
import com.aplat.seam.ToolCall;
import com.aplat.seam.ToolResult;
import com.aplat.seam.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 工具执行管线：五道判断各自可独立验证——这是"模块可单独测试"的样板。 */
class ToolPipelineTest {

    private DefaultToolRegistry registryWithEcho() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        BuiltinTools.registerAll(r);
        return r;
    }

    @Test
    @DisplayName("1) 幻觉防护：不存在的工具返回 UNKNOWN_TOOL，并告诉模型有哪些可用工具")
    void unknownToolReturnsStructuredError() {
        ToolPipeline pipeline = new ToolPipeline(registryWithEcho(), Hitl.autoAllow());
        ToolResult result = pipeline.execute("s1", ToolCall.of("delete_everything", "{}"));

        assertTrue(result.failed());
        assertEquals(ToolResult.ERR_UNKNOWN_TOOL, result.errorCode());
        assertTrue(result.content().contains("echo"), "应提示可用工具，便于模型自纠");
    }

    @Test
    @DisplayName("2) 参数不是合法 JSON 对象 → INVALID_ARGS")
    void invalidArgumentsRejected() {
        ToolPipeline pipeline = new ToolPipeline(registryWithEcho(), Hitl.autoAllow());
        ToolResult result = pipeline.execute("s1", new ToolCall("c1", "echo", "[not-an-object"));

        assertEquals(ToolResult.ERR_INVALID_ARGS, result.errorCode());
    }

    @Test
    @DisplayName("3) 危险命令被策略拦截，且拒绝理由可归因")
    void dangerousCommandBlocked() {
        DefaultToolRegistry r = registryWithEcho();
        // 执行类工具必须声明 commandField，策略层才做得成检查（见 ToolPolicyTest）
        r.register(Tool.of(ToolSpec.executing("shell", "shell", "{}", "command"),
                call -> ToolResult.ok("should not reach")));
        ToolPipeline pipeline = new ToolPipeline(r, Hitl.autoAllow());

        ToolResult result = pipeline.execute("s1",
                ToolCall.of("shell", "{\"command\":\"rm -rf /\"}"));

        assertTrue(result.failed());
        assertEquals(ToolResult.ERR_BLOCKED, result.errorCode());
        assertTrue(result.content().contains("DESTRUCTIVE_RM"));
    }

    @Test
    @DisplayName("4a) 需要批准的工具，人被拒绝 → DENIED，且提示模型换路子")
    void deniedByHumanIsObservable() {
        DefaultToolRegistry r = registryWithEcho();
        r.register(Tool.requiringApproval(new ToolSpec("shell", "shell", "{}"),
                call -> ToolResult.ok("executed")));
        ToolPipeline pipeline = new ToolPipeline(r, Hitl.autoDeny("too risky"));

        ToolResult result = pipeline.execute("s1", ToolCall.of("shell", "{\"command\":\"ls\"}"));

        assertEquals(ToolResult.ERR_DENIED, result.errorCode());
        assertTrue(result.content().contains("too risky"));
        assertTrue(result.content().contains("another approach"));
    }

    @Test
    @DisplayName("4b) 用户改参后再执行：生效的是改后的参数")
    void modifiedArgumentsAreUsed() {
        DefaultToolRegistry r = registryWithEcho();
        r.register(Tool.requiringApproval(new ToolSpec("shell", "shell", "{}"),
                call -> ToolResult.ok("args=" + call.argumentsJson())));
        Hitl modifying = new Hitl() {
            @Override
            public String id() {
                return "hitl.test-modify";
            }

            @Override
            public HitlDecision request(com.aplat.seam.HitlRequest request) {
                return new HitlDecision.Modified("{\"command\":\"echo modified-by-human\"}");
            }
        };
        ToolPipeline pipeline = new ToolPipeline(r, modifying);

        ToolResult result = pipeline.execute("s1", ToolCall.of("shell", "{\"command\":\"echo original\"}"));

        assertTrue(result.ok(), result.content());
        assertTrue(result.content().contains("echo modified-by-human"),
                "生效的必须是用户改后的参数，实际: " + result.content());
    }

    @Test
    @DisplayName("4c) 人没响应 → TIMEOUT，错误码与 DENIED 区分开")
    void timeoutDistinguishedFromDenial() {
        DefaultToolRegistry r = registryWithEcho();
        r.register(Tool.requiringApproval(new ToolSpec("shell", "shell", "{}"),
                call -> ToolResult.ok("executed")));
        Hitl timingOut = new Hitl() {
            @Override
            public String id() {
                return "hitl.test-timeout";
            }

            @Override
            public HitlDecision request(com.aplat.seam.HitlRequest request) {
                return new HitlDecision.Timeout();
            }
        };

        ToolResult result = new ToolPipeline(r, timingOut)
                .execute("s1", ToolCall.of("shell", "{\"command\":\"ls\"}"));

        assertEquals(ToolResult.ERR_TIMEOUT, result.errorCode());
    }

    @Test
    @DisplayName("5) 工具内部抛异常不穿透，被兜成 SANDBOX_FAILURE")
    void handlerExceptionDoesNotEscape() {
        DefaultToolRegistry r = new DefaultToolRegistry();
        r.register(Tool.of(new ToolSpec("boom", "boom", "{}"), call -> {
            throw new IllegalStateException("inner failure");
        }));

        ToolResult result = new ToolPipeline(r, Hitl.autoAllow())
                .execute("s1", ToolCall.of("boom", "{}"));

        assertEquals(ToolResult.ERR_SANDBOX, result.errorCode());
        assertTrue(result.content().contains("inner failure"));
    }

    @Test
    @DisplayName("注册表卸载后 schema 一并消失：模型不能再看到已移除的工具")
    void unregisterRemovesSchema() {
        DefaultToolRegistry r = registryWithEcho();
        assertTrue(r.contains("echo"));

        r.unregister("echo");
        assertFalse(r.contains("echo"));
        assertFalse(r.specs().stream().anyMatch(s -> s.name().equals("echo")),
                "schema 必须同步回收，否则模型会调用一个已经不存在的东西");
    }
}
