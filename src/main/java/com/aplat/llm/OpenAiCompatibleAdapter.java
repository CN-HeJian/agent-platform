package com.aplat.llm;

import com.aplat.seam.LlmAdapter;
import com.aplat.seam.LlmChunk;
import com.aplat.seam.LlmMessage;
import com.aplat.seam.LlmRequest;
import com.aplat.seam.ToolCall;
import com.aplat.seam.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容适配器（D1 决策的落地）。
 *
 * <p>只依赖 JDK 自带 HttpClient——不引任何 SDK，因为"OpenAI 兼容"本身就是事实标准协议，
 * 引 SDK 反而把可替换性锁死在某一家。
 *
 * <p>流式处理的两个易错点，这里显式处理了：
 * <ol>
 *   <li>工具调用在 SSE 里是**按 index 分片**下发的（name/arguments 各来几个字节），
 *       必须拼装完整才能当一次调用；</li>
 *   <li>{@code [DONE]} 与 {@code finish_reason} 都可能不出现，不能让循环卡等。</li>
 * </ol>
 */
public final class OpenAiCompatibleAdapter implements LlmAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final HttpClient client;

    public OpenAiCompatibleAdapter(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, Duration.ofMinutes(2));
    }

    public OpenAiCompatibleAdapter(String baseUrl, String apiKey, String model, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /** 从环境变量构造：{@code APLAT_LLM_BASE_URL} / {@code APLAT_LLM_API_KEY} / {@code APLAT_LLM_MODEL}。 */
    public static OpenAiCompatibleAdapter fromEnv() {
        String base = env("APLAT_LLM_BASE_URL", "https://api.openai.com/v1");
        String key = env("APLAT_LLM_API_KEY", "");
        String model = env("APLAT_LLM_MODEL", "gpt-4o-mini");
        return new OpenAiCompatibleAdapter(base, key, model);
    }

    public static boolean envConfigured() {
        String key = System.getenv("APLAT_LLM_API_KEY");
        return key != null && !key.isBlank();
    }

    private static String env(String k, String fallback) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? fallback : v;
    }

    @Override
    public String id() {
        return "llm.openai-compatible:" + model;
    }

    public String model() {
        return model;
    }

    @Override
    public void stream(LlmRequest request, Consumer<LlmChunk> sink) {
        try {
            String body = buildRequestBody(request);
            HttpRequest http = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> response =
                    client.send(http, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() >= 400) {
                String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                sink.accept(new LlmChunk.Done("error"));
                throw new LlmException("LLM HTTP " + response.statusCode() + ": " + truncate(err));
            }

            parseSse(response.body(), sink);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            sink.accept(new LlmChunk.Done("error"));
            throw new LlmException("LLM request failed: " + e.getMessage(), e);
        }
    }

    private void parseSse(java.io.InputStream in, Consumer<LlmChunk> sink) throws Exception {
        // index -> 累积中的工具调用
        Map<Integer, ToolCallBuilder> pending = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        String finishReason = null;

        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode node = MAPPER.readTree(data);
                JsonNode choice = node.path("choices").path(0);
                if (choice.isMissingNode()) {
                    continue;
                }
                JsonNode delta = choice.path("delta");

                JsonNode content = delta.get("content");
                if (content != null && !content.isNull() && !content.asText().isEmpty()) {
                    text.append(content.asText());
                    sink.accept(new LlmChunk.TextDelta(content.asText()));
                }

                JsonNode toolCalls = delta.get("tool_calls");
                if (toolCalls != null && toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        int idx = tc.path("index").asInt(0);
                        ToolCallBuilder b = pending.computeIfAbsent(idx, k -> new ToolCallBuilder());
                        if (tc.hasNonNull("id")) {
                            b.id = tc.get("id").asText();
                        }
                        JsonNode fn = tc.get("function");
                        if (fn != null) {
                            if (fn.hasNonNull("name")) {
                                b.name = fn.get("name").asText();
                            }
                            if (fn.hasNonNull("arguments")) {
                                b.arguments.append(fn.get("arguments").asText());
                            }
                        }
                    }
                }

                JsonNode fr = choice.get("finish_reason");
                if (fr != null && !fr.isNull()) {
                    finishReason = fr.asText();
                }
            }
        }

        // 拼装完成的工具调用一次性下发
        for (ToolCallBuilder b : pending.values()) {
            if (b.name != null) {
                sink.accept(new LlmChunk.ToolCallDelta(
                        new ToolCall(b.id == null ? ToolCall.of(b.name, b.arguments.toString()).id() : b.id,
                                b.name, b.arguments.toString())));
            }
        }
        sink.accept(new LlmChunk.Usage(BudgetContextProviderShim.estimate(text.length()), 0));
        sink.accept(new LlmChunk.Done(finishReason == null ? "stop" : finishReason));
    }

    private String buildRequestBody(LlmRequest request) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("stream", true);

        ArrayNode msgs = root.putArray("messages");
        for (LlmMessage m : request.messages()) {
            ObjectNode n = msgs.addObject();
            n.put("role", m.role());
            n.put("content", m.content() == null ? "" : m.content());
            if (m.toolCallId() != null) {
                n.put("tool_call_id", m.toolCallId());
            }
        }

        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                ObjectNode t = tools.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", spec.name());
                fn.put("description", spec.description());
                fn.set("parameters", MAPPER.readTree(
                        spec.parametersJson() == null || spec.parametersJson().isBlank()
                                ? "{\"type\":\"object\",\"properties\":{}}"
                                : spec.parametersJson()));
            }
        }
        if (request.maxOutputTokens() != null) {
            root.put("max_tokens", request.maxOutputTokens());
        }
        return MAPPER.writeValueAsString(root);
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }

    private static final class ToolCallBuilder {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    /** 兼容占位：只在估算用量时用，避免 llm 包反向依赖 context 包。 */
    private static final class BudgetContextProviderShim {
        static int estimate(int chars) {
            return chars / 4;
        }
    }

    public static final class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
