package com.aplat.llm;

import com.aplat.seam.LlmAdapter;
import com.aplat.seam.LlmChunk;
import com.aplat.seam.LlmRequest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * 脚本化 LLM：按预置脚本依次吐出 chunk。
 *
 * <p>它不是"测试替身"那么简单——它让**循环逻辑本身**可以脱离模型和网络被验证：
 * 幻觉防护、预算终止、HITL 拒绝后的改道，全都靠它构造确定性场景。
 * 每次 {@code stream()} 还记录收到的请求，用于断言"上下文压缩后模型到底看到了什么"。
 */
public final class ScriptedLlmAdapter implements LlmAdapter {

    /** 每个 Stream 输出脚本的工厂：拿到请求，决定这一步吐什么。 */
    @FunctionalInterface
    public interface Step {
        List<LlmChunk> produce(LlmRequest request);
    }

    private final Deque<Step> script = new ArrayDeque<>();
    private final List<LlmRequest> received = new ArrayList<>();
    private final List<String> stepLabels = new ArrayList<>();

    public ScriptedLlmAdapter() {
    }

    /** 追加一步：输出纯文本并收口。 */
    public ScriptedLlmAdapter thenText(String text) {
        return then(label("text:" + shortOf(text)), req -> List.of(
                new LlmChunk.TextDelta(text),
                new LlmChunk.Done("stop")));
    }

    /** 追加一步：发起工具调用。 */
    public ScriptedLlmAdapter thenToolCall(String tool, String argsJson) {
        return then(label("call:" + tool), req -> List.of(
                new LlmChunk.ToolCallDelta(com.aplat.seam.ToolCall.of(tool, argsJson)),
                new LlmChunk.Done("tool_calls")));
    }

    /** 追加一步：自定义行为（可以拿到请求做断言或动态决策）。 */
    public ScriptedLlmAdapter then(String label, Step step) {
        script.addLast(new LabelledStep(label, step));
        stepLabels.add(label);
        return this;
    }

    private static String label(String s) {
        return s;
    }

    private static String shortOf(String s) {
        return s.length() <= 20 ? s : s.substring(0, 20) + "...";
    }

    @Override
    public String id() {
        return "llm.scripted";
    }

    @Override
    public void stream(LlmRequest request, Consumer<LlmChunk> sink) {
        received.add(request);
        Step next = script.pollFirst();
        if (next == null) {
            // 脚本用尽：不要抛异常（那会让"预算终止"这类测试失真），给一个显式收口
            sink.accept(new LlmChunk.TextDelta("[script exhausted]"));
            sink.accept(new LlmChunk.Done("stop"));
            return;
        }
        for (LlmChunk chunk : next.produce(request)) {
            sink.accept(chunk);
        }
    }

    /** 已消费到的步标号，便于断言"跑了几步"。 */
    public List<String> consumed() {
        return new ArrayList<>(stepLabels.subList(0, received.size()));
    }

    /** 每次 stream 收到的请求快照。 */
    public List<LlmRequest> requests() {
        return List.copyOf(received);
    }

    private record LabelledStep(String label, Step step) implements Step {
        @Override
        public List<LlmChunk> produce(LlmRequest request) {
            return step.produce(request);
        }
    }
}
