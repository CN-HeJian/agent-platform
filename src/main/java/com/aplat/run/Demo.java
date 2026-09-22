package com.aplat.run;

import com.aplat.kernel.Kernel;
import com.aplat.llm.OpenAiCompatibleAdapter;
import com.aplat.llm.ScriptedLlmAdapter;
import com.aplat.loop.LoopBudget;
import com.aplat.loop.TurnResult;
import com.aplat.sandbox.DockerSandbox;
import com.aplat.sandbox.ProcessSandbox;
import com.aplat.seam.Hitl;
import com.aplat.seam.LlmAdapter;
import com.aplat.seam.Sandbox;
import com.aplat.seam.SessionEvent;
import com.aplat.session.AgUiMapper;
import java.util.Map;

/**
 * 可运行的演示。两种模式：
 *
 * <pre>
 *   # 1) 离线：脚本化模型 + 进程沙箱（无需网络、无需 Docker）
 *   mvn -q compile exec:java
 *
 *   # 2) 真实模型：任何 OpenAI 兼容服务
 *   export APLAT_LLM_BASE_URL=https://api.deepseek.com/v1
 *   export APLAT_LLM_API_KEY=sk-xxx
 *   export APLAT_LLM_MODEL=deepseek-chat
 *   mvn -q compile exec:java
 * </pre>
 */
public final class Demo {

    public static void main(String[] args) {
        boolean real = OpenAiCompatibleAdapter.envConfigured();

        Sandbox sandbox = DockerSandbox.defaultImage().available()
                ? DockerSandbox.defaultImage()
                : new ProcessSandbox();

        LlmAdapter llm = real
                ? OpenAiCompatibleAdapter.fromEnv()
                : new ScriptedLlmAdapter()
                        .thenToolCall("shell", "{\"command\":\"echo hello-from-sandbox\"}")
                        .thenText("命令已执行，输出为 hello-from-sandbox。");

        Platform platform = Platform.assemble(llm, sandbox, Hitl.autoAllow(), LoopBudget.defaults());

        System.out.println("=== 装配清单 ===");
        System.out.println(platform.assemblyReport());
        System.out.println("LLM     : " + llm.id());
        System.out.println("Sandbox : " + sandbox.description());
        System.out.println("模式    : " + (real ? "真实 OpenAI 兼容服务" : "离线脚本化模型"));

        String sessionId = "demo-" + System.currentTimeMillis();
        String input = real
                ? "用一条 shell 命令打印当前工作目录，然后告诉我结果。"
                : "帮我在沙箱里跑一条命令。";

        System.out.println("\n=== 一次 turn ===");
        System.out.println("input > " + input);

        TurnResult result = platform.loop().run(sessionId, input);

        System.out.println("\n=== AG-UI 事件流（前端看到的就是这个） ===");
        for (SessionEvent e : result.events()) {
            Map<String, Object> agui = AgUiMapper.toAgUi(e);
            System.out.printf("%-28s %s%n", agui.get("type"), trim(String.valueOf(agui.get("payload"))));
        }

        System.out.println("\n=== 结果 ===");
        System.out.println("status : " + result.status());
        System.out.println("steps  : " + result.steps());
        System.out.println("answer : " + result.finalText());

        System.out.println("\n=== 会话回放（排查用；模型看到的每一件事都在这里） ===");
        System.out.println(platform.sessionLog().replay(sessionId));

        Kernel kernel = platform.kernel();
        System.out.println("可替换能力缝: " + kernel.registry().boundSeams().size() + " 条已装配");
    }

    private static String trim(String s) {
        return s.length() <= 110 ? s : s.substring(0, 110) + "...";
    }
}
