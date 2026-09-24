package com.aplat.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aplat.llm.ScriptedLlmAdapter;
import com.aplat.seam.LlmAdapter;
import com.aplat.seam.ToolCall;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 一条评测用例（U27）。
 *
 * <pre>
 * {"id":"shell-hello","input":"用 shell 打印当前目录",
 *  "script":[{"kind":"tool","tool":"shell","args":{"command":"echo hi"}},
 *            {"kind":"text","text":"已执行"}],
 *  "expectStatus":"COMPLETED",
 *  "expectTools":["shell"],
 *  "forbidTools":["rm_rf_everything"],
 *  "expectText":["已执行"],
 *  "maxSteps":4}
 * </pre>
 *
 * <h2>为什么用例自带 {@code script}</h2>
 *
 * <p>没有它的话，"跑一遍评测"就必须调真实模型，于是：慢、贵、不确定（同一个用例两次跑出不同结果），
 * 而且**一旦失败，你不知道是循环坏了还是模型那天心情不好**。
 *
 * <p>脚本化之后，评测变成了对**平台行为**的确定断言：给定这串模型输出，工具该不该被调、
 * 观察该不该回填、预算该不该终止。这正是"改了循环/提示词/策略之后有没有变坏"这个问题要的答案。
 * 真实模型评测是另一件事——同一个 dataset 换一个 adapter 就能跑（见 {@code EvalRunner} 的
 * {@code adapterFactory}），但那是"模型好不好"，不是"平台对不对"。
 *
 * <h2>{@code forbidTools} 是这套东西里最值钱的一条</h2>
 *
 * <p>与 {@code expectToolFailures} 配合，这两条是**唯一能抓住"安全检查不再生效"的东西**：
 * 危险命令被拦下时，工具调用本身仍然发生了（{@code expectTools} 照样满足），
 * 最终措辞也可能一字不变（{@code expectText} 照样满足）——只有"结果里必须出现这个错误码"
 * 才真的钉住了"它被拦下了"。这个缺口是本项目做飞轮演示时发现的：
 * 把危险命令检查摘掉，数据集**全绿**。
 *
 * <p>它的匹配口径是"工具结果的**错误码或原因文本**里出现这个字符串"——
 * 因为被拦下时事件里的 {@code errorCode} 是通用的 {@code BLOCKED_BY_POLICY}，
 * 而"具体是哪条规则拦的"（如 {@code DESTRUCTIVE_RM}）在原因文本里。
 * 只断言通用码的话，"任何一次策略拦截"都能满足它，那这个断言就没什么用了。
 * 一次提示词改动让模型开始调 {@code rm_rf} 而没有人发现，代价与"某个功能没做出来"不是一个量级。
 */
public record EvalCase(
        String id,
        String input,
        String sessionId,
        List<Step> script,
        Set<String> expectStatus,
        List<String> expectTools,
        List<String> forbidTools,
        List<String> expectText,
        List<String> expectToolFailures,
        int maxSteps,
        String note) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public EvalCase {
        script = script == null ? List.of() : List.copyOf(script);
        expectStatus = expectStatus == null ? Set.of() : Set.copyOf(expectStatus);
        expectTools = expectTools == null ? List.of() : List.copyOf(expectTools);
        forbidTools = forbidTools == null ? List.of() : List.copyOf(forbidTools);
        expectText = expectText == null ? List.of() : List.copyOf(expectText);
        expectToolFailures = expectToolFailures == null ? List.of() : List.copyOf(expectToolFailures);
        if (maxSteps <= 0) {
            maxSteps = 4;
        }
        sessionId = sessionId == null || sessionId.isBlank() ? "eval-" + id : sessionId;
        note = note == null ? "" : note;
    }

    /** 脚本里的一步：要么吐文字，要么发起一次工具调用。 */
    public record Step(String kind, String tool, String argsJson, String text) {

        static Step ofJson(JsonNode n) {
            String kind = n.path("kind").asText("text");
            if ("tool".equals(kind)) {
                return new Step("tool", n.path("tool").asText(""),
                        n.path("args").isMissingNode() ? "{}" : n.path("args").toString(), null);
            }
            return new Step("text", null, null, n.path("text").asText(""));
        }

        ObjectNode toJson() {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("kind", kind);
            if ("tool".equals(kind)) {
                n.put("tool", tool);
                try {
                    n.set("args", MAPPER.readTree(argsJson));
                } catch (Exception e) {
                    n.put("argsJson", argsJson);
                }
            } else {
                n.put("text", text);
            }
            return n;
        }
    }

    /** 用例自带的脚本 → 一个确定性的模型。 */
    public LlmAdapter asAdapter() {
        ScriptedLlmAdapter adapter = new ScriptedLlmAdapter();
        if (script.isEmpty()) {
            // 没有脚本 = "模型什么工具都不调，直接回一句话"。这是最该被覆盖的一条：
            // 纯聊天路径也要过评测，否则它是最容易悄悄坏掉、而没人发现的那条。
            return adapter.thenText("");
        }
        for (Step s : script) {
            if ("tool".equals(s.kind())) {
                adapter.thenToolCall(s.tool(), s.argsJson());
            } else {
                adapter.thenText(s.text() == null ? "" : s.text());
            }
        }
        return adapter;
    }

    // ------------------------------------------------------------------ JSONL

    public static List<EvalCase> load(Path jsonl) throws IOException {
        List<EvalCase> out = new ArrayList<>();
        int lineNo = 0;
        for (String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
            lineNo++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }
            try {
                out.add(fromJson(MAPPER.readTree(trimmed)));
            } catch (Exception e) {
                // 数据集里的一行写错，必须报出**是哪一行**。否则你只会看到
                // "评测少跑了一条"，而少跑的那条通常正是新加的那条。
                throw new IllegalArgumentException(
                        jsonl + " 第 " + lineNo + " 行不是合法的用例: " + e.getMessage(), e);
            }
        }
        return out;
    }

    public static EvalCase fromJson(JsonNode n) {
        List<Step> steps = new ArrayList<>();
        for (JsonNode s : n.path("script")) {
            steps.add(Step.ofJson(s));
        }
        Set<String> statuses = new java.util.LinkedHashSet<>();
        JsonNode status = n.path("expectStatus");
        if (status.isArray()) {
            status.forEach(x -> statuses.add(x.asText()));
        } else if (!status.isMissingNode() && !status.asText().isBlank()) {
            statuses.add(status.asText());
        }
        return new EvalCase(
                n.path("id").asText(""),
                n.path("input").asText(""),
                n.path("sessionId").asText(""),
                steps,
                statuses,
                strings(n.path("expectTools")),
                strings(n.path("forbidTools")),
                strings(n.path("expectText")),
                strings(n.path("expectToolFailures")),
                n.path("maxSteps").asInt(4),
                n.path("note").asText(""));
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(x -> out.add(x.asText()));
        return out;
    }

    public ObjectNode toJson() {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", id);
        n.put("input", input);
        n.put("maxSteps", maxSteps);
        if (sessionId != null && !sessionId.startsWith("eval-")) {
            n.put("sessionId", sessionId);
        }
        ArrayNode arr = n.putArray("script");
        script.forEach(s -> arr.add(s.toJson()));
        if (!expectStatus.isEmpty()) {
            ArrayNode st = n.putArray("expectStatus");
            expectStatus.forEach(st::add);
        }
        if (!expectTools.isEmpty()) {
            ArrayNode t = n.putArray("expectTools");
            expectTools.forEach(t::add);
        }
        if (!forbidTools.isEmpty()) {
            ArrayNode f = n.putArray("forbidTools");
            forbidTools.forEach(f::add);
        }
        if (!expectText.isEmpty()) {
            ArrayNode t = n.putArray("expectText");
            expectText.forEach(t::add);
        }
        if (!expectToolFailures.isEmpty()) {
            ArrayNode t = n.putArray("expectToolFailures");
            expectToolFailures.forEach(t::add);
        }
        if (!note.isEmpty()) {
            n.put("note", note);
        }
        return n;
    }

    /** 一次调用里的第一个工具名（用于断言；没有调用则返回 null）。 */
    public static String firstToolName(ToolCall call) {
        return call == null ? null : call.name();
    }
}
