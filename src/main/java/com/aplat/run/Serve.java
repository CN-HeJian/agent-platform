package com.aplat.run;

import com.aplat.llm.OpenAiCompatibleAdapter;
import com.aplat.llm.ScriptedLlmAdapter;
import com.aplat.loop.LoopBudget;
import com.aplat.sandbox.DockerSandbox;
import com.aplat.sandbox.ProcessSandbox;
import com.aplat.seam.Hitl;
import com.aplat.seam.LlmAdapter;
import com.aplat.seam.Sandbox;
import com.aplat.tools.DefaultToolPolicy;
import com.aplat.web.HttpTransport;
import com.aplat.web.ServerConfig;

/**
 * 起服务：把薄内核挂到 HTTP + SSE 上（U02 的入口）。
 *
 * <pre>
 *   # 离线（脚本化模型，零配置）
 *   ./mvnw -q compile exec:java@serve
 *
 *   # 真实模型（任何 OpenAI 兼容服务）
 *   export APLAT_LLM_BASE_URL=https://api.deepseek.com/v1
 *   export APLAT_LLM_API_KEY=sk-xxx
 *   export APLAT_LLM_MODEL=deepseek-chat
 *   ./mvnw -q compile exec:java@serve
 *
 *   # 换了端口 / 需要带 key
 *   export APLAT_HTTP_PORT=8080
 *   export APLAT_API_KEY=dev-secret
 * </pre>
 *
 * 注意用 {@code exec:java@serve} 而不是 {@code -Dexec.mainClass}：POM 里显式配置的
 * {@code mainClass} 优先级高于用户属性，要让 {@code -D} 生效得先把它从 configuration 里删掉。
 * 两个入口做成了两个 execution id（{@code demo} / {@code serve}），互不干扰。
 *
 * 默认只监听 127.0.0.1：这个服务能执行 shell，不该在你不注意的时候对外可达。
 */
public final class Serve {

    public static void main(String[] args) throws Exception {
        ServerConfig config = ServerConfig.fromEnv();
        boolean realModel = OpenAiCompatibleAdapter.envConfigured();

        Sandbox sandbox = DockerSandbox.defaultImage().available()
                ? DockerSandbox.defaultImage()
                : new ProcessSandbox();

        LlmAdapter llm = realModel
                ? OpenAiCompatibleAdapter.fromEnv()
                // 离线模式给一段固定剧本，便于"不打网络也能看到完整流式链路"。
                // repeat()：服务是要被反复戳的，脚本不该第二次请求就"用尽"。
                : new ScriptedLlmAdapter()
                        .thenToolCall("shell", "{\"command\":\"echo hello-from-http\"}")
                        .thenText("已在沙箱中执行命令，输出为 hello-from-http。")
                        .repeat();

        Platform platform = Platform.assemble(llm, sandbox, Hitl.autoAllow(), LoopBudget.defaults(),
                DefaultToolPolicy.fromEnv());

        System.out.println("=== 装配清单 ===");
        System.out.println(platform.assemblyReport());
        for (String warning : platform.policyWarnings()) {
            System.out.println("[policy][warn] " + warning);
        }
        System.out.println("LLM     : " + llm.id() + (realModel ? "（真实服务）" : "（离线脚本）"));
        System.out.println("Sandbox : " + sandbox.description());

        HttpTransport transport = new HttpTransport(platform, config).start();
        Runtime.getRuntime().addShutdownHook(new Thread(transport::close, "shutdown"));

        String base = transport.baseUrl();
        System.out.println();
        System.out.println("=== 已启动 " + base + " ===");
        if (config.authEnabled()) {
            System.out.println("鉴权    : X-API-Key 已启用（/health 与 /ui/ 静态资源豁免）");
        } else {
            System.out.println("鉴权    : 未启用（仅监听 " + config.host() + "；对外暴露前请设 APLAT_API_KEY）");
        }
        System.out.println();
        System.out.println("前端会话面 : " + base + "/ui/         ← CopilotKit（U07a）");
        System.out.println("调试控制台 : " + base + "/            ← 自带的最小控制台");
        System.out.println("AG-UI 端点 : POST " + base + "/agui/run   ← 前端接的就是它");
        transport.uiAssetsHint().ifPresent(hint -> System.out.println("[ui][warn] " + hint));
        System.out.println();
        System.out.println("健康检查   : curl -s " + base + "/health");
        System.out.println("同步跑一次 : curl -s -X POST " + base + "/run \\");
        System.out.println("               -H 'Content-Type: application/json' \\");
        System.out.println("               -d '{\"input\":\"用 shell 打印当前目录\"}'");
        System.out.println("AG-UI 手测 : curl -N -X POST " + base + "/agui/run \\");
        System.out.println("               -H 'Content-Type: application/json' \\");
        System.out.println("               -d '{\"threadId\":\"t1\",\"runId\":\"r1\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}'");
        System.out.println("简化流     : curl -N '" + base + "/agui/stream?input=hello'（自带控制台用）");
        System.out.println("续传       : curl -N '" + base
                + "/agui/events/<sessionId>?lastEventId=7&once=turn.closed'");
        System.out.println();
        System.out.println("按 Ctrl+C 停止。");

        // 常驻：HttpServer 自己跑在后台线程池上，主线程只需挂住
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
