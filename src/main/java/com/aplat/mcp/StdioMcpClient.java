package com.aplat.mcp;

import com.aplat.seam.McpClient;
import com.aplat.seam.ToolCall;
import com.aplat.seam.ToolHandler;
import com.aplat.seam.ToolResult;
import com.aplat.seam.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * stdio 传输的 MCP 客户端（U24）：起一个子进程，跟它按行说 JSON-RPC。
 *
 * <h2>一个始终在跑的读线程，而不是"发一次读一次"</h2>
 *
 * <p>看起来更简单的做法是：写完请求，然后同步读到"id 对上的那一行"。但那样有两个必然的坑：
 * <ol>
 *   <li>服务端可能先发**通知**（日志、进度）再发响应。同步读的第一行往往不是我们要的响应，
 *       于是要么丢消息、要么自己写状态机；</li>
 *   <li>服务端可能在**没有任何请求**的时候推送消息（MCP 允许），同步读就再也读不到了。</li>
 * </ol>
 *
 * <p>所以这里有一个守护读线程，按 {@code id} 把响应派发回对应的 future。
 * 读线程遇到读不懂的行只记一笔、不退出——**一行噪声不能杀掉整条连接**。
 *
 * <h2>{@code healthy()} 是"能不能真正干活"，不是"进程还在不在"</h2>
 *
 * <p>子进程可能活着但不响应（死锁、卡在启动、等输入）。所以健康检查发一个真的
 * {@code tools/list} 并等回复；超时就算不健康。这个问题在接入 MCP 时一定会遇到：
 * "进程在但工具没了"是最难查的一种。
 *
 * <h2>超时必须有上限</h2>
 *
 * <p>每次请求都带超时。远端工具卡住时，我们的 Agent 循环不能跟着一起卡——
 * 它会等在 {@code ToolHandler} 里，而那个等待没有上限就等于整个 turn 没有上限。
 */
public final class StdioMcpClient implements McpClient {

    /** 单次请求的等待上限。远端工具的正常耗时是这个量级的，卡住时也要能放弃。 */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final String endpoint;
    private final Duration requestTimeout;
    /** 工具名前缀。显式给最好——从命令行猜出来的名字会长得没法看（见 nameOf）。 */
    private final String name;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean broken = new AtomicBoolean();

    private Process process;
    private PrintWriter stdin;
    private Thread reader;
    private final List<String> noise = new java.util.concurrent.CopyOnWriteArrayList<>();

    public StdioMcpClient(String endpoint) {
        this(endpoint, DEFAULT_REQUEST_TIMEOUT, null);
    }

    public StdioMcpClient(String endpoint, Duration requestTimeout) {
        this(endpoint, requestTimeout, null);
    }

    /**
     * @param name 工具名前缀；null = 从命令行末段猜一个（见 {@link #nameOf}）。
     *             能显式给就显式给——猜出来的前缀会跟着命令行变化，而工具名是模型要看到、
     *             人要写进配置的东西，它不该随"python 换成 python3"而变。
     */
    public StdioMcpClient(String endpoint, Duration requestTimeout, String name) {
        this.endpoint = endpoint;
        this.requestTimeout = requestTimeout;
        this.name = name == null || name.isBlank() ? nameOf(endpoint) : sanitize(name);
    }

    /**
     * 从命令行里猜一个前缀：取最后一个参数的文件名去掉扩展名。
     *
     * <p>{@code "python3 tools/mcp_server.py"} → {@code mcp_server}；
     * {@code "node other.js"} → {@code other}。
     * 猜不出来（比如是一长串 classpath）时退化成一个短哈希——**短**很重要：
     * 前缀会出现在每个工具名里，而工具名是模型上下文的一部分。
     */
    private static String nameOf(String endpoint) {
        String[] tokens = endpoint.trim().split("\\s+");
        String last = tokens[tokens.length - 1];
        // 只去掉**最后一个**扩展名：replaceAll("\\..*$") 会从第一个点砍起，
        // 于是 "com.aplat.mcp.StdioMcpServer" 变成 "com"——名字短到没有信息量
        String base = last.replaceAll(".*/", "").replaceAll("\\.[^.]*$", "");
        String cleaned = sanitize(base);
        if (cleaned.length() >= 3 && cleaned.length() <= 16) {
            return cleaned;
        }
        return "e" + Integer.toHexString(endpoint.hashCode() & 0xffff);
    }

    private static String sanitize(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }

    @Override
    public String id() {
        return "mcp.stdio[" + endpoint + "]";
    }

    // ------------------------------------------------------------------ 发现

    @Override
    public List<Binding> discover(String endpoint) {
        if (!start()) {
            return List.of();
        }
        JsonNode tools = call("tools/list", Map.of()).path("result").path("tools");
        if (!tools.isArray()) {
            broken.set(true);
            return List.of();
        }
        List<Binding> out = new ArrayList<>();
        for (JsonNode t : tools) {
            String name = t.path("name").asText("");
            if (name.isBlank()) {
                continue; // 没有名字的工具没法被调用、也没法被卸载，收进来只会变成死重
            }
            // 名字加前缀：不同 MCP 端点常常提供同名工具（两个 filesystem 服务都叫 read_file），
            // 不加前缀就会在注册表里互相覆盖——而且覆盖是静默的。
            String exposed = endpointPrefix() + name;
            ToolSpec spec = new ToolSpec(exposed,
                    t.path("description").asText("") + "（远端 MCP 工具：" + name + "）",
                    t.path("inputSchema").toString(),
                    commandFieldOf(t.path("inputSchema")));
            out.add(new Binding(spec, handlerFor(name)));
        }
        return out;
    }

    /**
     * 从 inputSchema 里认"哪个参数是命令/脚本"。
     *
     * <p>这**是一种猜测**，而且是必要的一种：MCP 的 {@code tools/list} 里没有"这个工具会
     * 执行命令"的声明（只有可选的 {@code annotations}，而多数服务端不填）。
     * 不猜的话，接进来的远端 shell 工具在我们的策略层里就是"纯计算类"，危险命令检查整条失效。
     *
     * <p>猜的依据是参数名（{@code command/script/cmd/code}）。猜错的代价是不对称的：
     * 把纯计算工具误判成执行类 → 多查一道（无害）；把执行类漏判 → 安全检查失效（有害）。
     * 所以宁可多猜。真正稳妥的方式是让 {@link McpMount} 显式声明，
     * 这里的猜测只是**兜底的默认**。
     */
    private static String commandFieldOf(JsonNode schema) {
        JsonNode required = schema.path("required");
        List<String> candidates = new ArrayList<>();
        if (required.isArray()) {
            required.forEach(n -> candidates.add(n.asText()));
        }
        schema.path("properties").fieldNames().forEachRemaining(candidates::add);
        for (String c : candidates) {
            String lower = c.toLowerCase();
            if (lower.contains("command") || lower.contains("script") || lower.equals("cmd")
                    || lower.contains("code") || lower.contains("sql")) {
                return c;
            }
        }
        return null;
    }

    /** 工具名 = {@code mcp_<name>_<远端原名>}。加前缀的理由：两个端点可能提供同名工具。 */
    private String endpointPrefix() {
        return "mcp_" + name + "_";
    }

    /** 前缀本身也要能看：写配置的人得知道信任名单里该填什么名字。 */
    public String toolNamePrefix() {
        return endpointPrefix();
    }

    /** 调用远端：把结果里的 content 拼成一段文本。 */
    private ToolHandler handlerFor(String remoteName) {
        return (ToolCall call) -> {
            if (broken.get()) {
                return ToolResult.error(ToolResult.ERR_SANDBOX,
                        "MCP endpoint " + endpoint + " is unhealthy; do not retry this tool.");
            }
            JsonNode args = JsonRpc.MAPPER.readTree(call.argumentsJson());
            JsonNode response = call("tools/call", Map.of("name", remoteName, "arguments", args));
            JsonNode error = response.path("error");
            if (!error.isMissingNode()) {
                return ToolResult.error(ToolResult.ERR_SANDBOX,
                        "MCP error: " + error.path("message").asText());
            }
            JsonNode result = response.path("result");
            StringBuilder text = new StringBuilder();
            for (JsonNode c : result.path("content")) {
                if ("text".equals(c.path("type").asText())) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(c.path("text").asText());
                }
            }
            boolean isError = result.path("isError").asBoolean(false);
            String content = text.length() == 0 ? "(远端返回了非文本内容)" : text.toString();
            return isError
                    ? ToolResult.error(ToolResult.ERR_SANDBOX, content)
                    : ToolResult.ok(content);
        };
    }

    // ------------------------------------------------------------------ 健康

    @Override
    public boolean healthy(String endpoint) {
        if (broken.get() || !start()) {
            return false;
        }
        JsonNode response = call("tools/list", Map.of());
        return response.has("result");
    }

    /** 读线程收下的"读不懂的行"。给排查用——对端把日志打到 stdout 是极常见的坑。 */
    public List<String> noise() {
        return List.copyOf(noise);
    }

    // ------------------------------------------------------------------ 传输

    private synchronized boolean start() {
        if (process != null && process.isAlive()) {
            return true;
        }
        try {
            // 用 shell 起：endpoint 是一行命令（例如 "python3 server.py"），
            // 让操作者能像在终端里那样写它，而不是被迫填 argv 数组
            process = new ProcessBuilder("/bin/sh", "-c", endpoint)
                    .redirectErrorStream(false)
                    .start();
            stdin = new PrintWriter(new OutputStreamWriter(process.getOutputStream(),
                    StandardCharsets.UTF_8), true);
            BufferedReader stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            reader = new Thread(() -> readLoop(stdout), "mcp-reader-" + endpointPrefix());
            reader.setDaemon(true);
            reader.start();

            // 握手。失败也没有立刻放弃——这里返回 true 让调用方走到 tools/list 那一步，
            // 因为"连上了但不会说话"和"根本连不上"对使用者的含义不一样。
            call("initialize", Map.of(
                    "protocolVersion", JsonRpc.PROTOCOL_VERSION,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "aplat", "version", "1.0.0")));
            send(JsonRpc.notification("notifications/initialized", null));
            return true;
        } catch (Exception e) {
            broken.set(true);
            return false;
        }
    }

    private void readLoop(BufferedReader stdout) {
        try {
            String line;
            while ((line = stdout.readLine()) != null) {
                JsonNode node = JsonRpc.parse(line);
                if (node == null) {
                    // 对端往 stdout 打了日志（这是 MCP 最常见的调试坑：日志必须走 stderr）。
                    // 记下来但不退出——一行噪声不该杀掉整条连接。
                    if (noise.size() < 100) {
                        noise.add(line);
                    }
                    continue;
                }
                if (!node.hasNonNull("id")) {
                    continue; // 通知
                }
                CompletableFuture<JsonNode> f = pending.remove(node.path("id").asLong());
                if (f != null) {
                    f.complete(node);
                }
            }
        } catch (Exception e) {
            // 子进程退了
        } finally {
            broken.set(true);
            // 把所有等着的请求叫醒：不叫醒的话调用方会一直等到各自超时，
            // 而在"进程已经没了"这件事很明确的时候，让它们多等 20 秒毫无意义。
            pending.values().forEach(f -> f.completeExceptionally(
                    new IllegalStateException("MCP process for " + endpoint + " has exited")));
            pending.clear();
        }
    }

    private void send(String line) {
        PrintWriter out = stdin;
        if (out == null) {
            throw new IllegalStateException("MCP client not started: " + endpoint);
        }
        synchronized (this) {
            out.println(line);
            out.flush();
            if (out.checkError()) {
                broken.set(true);
                throw new IllegalStateException("MCP stdin closed: " + endpoint);
            }
        }
    }

    /** 发一个请求并等它的响应。超时/进程死掉都返回一个空的 ObjectNode（调用方按缺字段处理）。 */
    private JsonNode call(String method, Object params) {
        long id = JsonRpc.nextId();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            send(JsonRpc.request(id, method, params));
            return future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            pending.remove(id);
            return JsonRpc.MAPPER.createObjectNode();
        }
    }

    public synchronized void close() {
        if (process != null) {
            process.destroy();
            process = null;
        }
        broken.set(true);
    }
}
