package com.xucai.aigc.procurement.chat;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.databind.*;
import com.xucai.aigc.procurement.ModelCallException;

import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Spring AI transports messages; only the application orchestrator executes business tools. */
@Component
@Profile("xucai")
public class DashScopeAssistantModel implements AssistantModel {
    @Override public String modelName() { return model; }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String key, model;
    private final boolean enabled;
    private final org.springframework.ai.chat.client.ChatClient chat;
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final Duration timeout;

    @Autowired
    public DashScopeAssistantModel(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            @Value("${xucai.ai.dashscope.key:}") String key,
            @Value("${xucai.procurement.model.name:qwen3.7-flash-2026-07-15}") String model,
            @Value("${xucai.procurement.model.enabled:false}") boolean enabled) {
        this(jdbc, mapper, key, model, enabled, createChat(key, mapper), Duration.ofSeconds(45));
    }

    DashScopeAssistantModel(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            String key,
            String model,
            boolean enabled,
            ChatModel chat,
            Duration timeout) {
        if (!Set.of("qwen3.7-flash-2026-07-15", "qwen-plus-2025-12-01").contains(model))
            throw new IllegalArgumentException("Unsupported assistant model");
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.key = key;
        this.model = model;
        this.enabled = enabled;
        this.chat =
                org.springframework.ai.chat.client.ChatClient.builder(chat)
                        .defaultAdvisors(new AssistantUsageAdvisor())
                        .build();
        this.timeout = timeout;
    }

    private static ChatModel createChat(String key, ObjectMapper mapper) {
        return createChat(key, mapper, "https://dashscope.aliyuncs.com");
    }

    static ChatModel createChat(String key, ObjectMapper mapper, String baseUrl) {
        return com.xucai.aigc.procurement.ProcurementChatModels.create(
                key, mapper, baseUrl, 60000, Duration.ofSeconds(45));
    }

    @Override
    public boolean available() {
        return enabled && !key.isBlank() && !key.equals("disabled-in-slice-0");
    }

    @Override
    public JsonNode call(
            String turn,
            int round,
            String agent,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            boolean json) {
        return invoke(turn, round, agent, messages, tools, json, null);
    }

    @Override
    public JsonNode stream(
            String turn,
            int round,
            String agent,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            Consumer<String> text) {
        return invoke(turn, round, agent, messages, tools, false, text);
    }

    @Override
    public void cancel(String turn) {
        var active = running.get(turn);
        if (active != null) {
            active.canceled.set(true);
            active.stop.tryEmitEmpty();
        }
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        running.keySet().forEach(this::cancel);
    }

    Prompt prompt(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            boolean json,
            String agent,
            int round) {
        List<Message> converted = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        for (var raw : messages) {
            var node = mapper.valueToTree(raw);
            String content = node.path("content").asText("");
            switch (node.path("role").asText()) {
                case "system" -> converted.add(new SystemMessage(content));
                case "user" -> converted.add(new UserMessage(content));
                case "assistant" -> {
                    List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                    for (var call : node.path("tool_calls")) {
                        String id = call.path("id").asText(),
                                name = call.path("function").path("name").asText();
                        names.put(id, name);
                        calls.add(
                                new AssistantMessage.ToolCall(
                                        id,
                                        "function",
                                        name,
                                        call.path("function").path("arguments").asText()));
                    }
                    converted.add(new AssistantMessage(content, Map.of(), calls));
                }
                case "tool" -> {
                    String id = node.path("tool_call_id").asText();
                    if (!names.containsKey(id))
                        throw new ModelCallException("MODEL_INPUT", "工具结果缺少对应调用");
                    converted.add(
                            new ToolResponseMessage(
                                    List.of(
                                            new ToolResponseMessage.ToolResponse(
                                                    id, names.get(id), content))));
                }
                default -> throw new ModelCallException("MODEL_INPUT", "消息角色无效");
            }
        }
        var options =
                DashScopeChatOptions.builder()
                        .withModel(model)
                        .withTemperature(0.0)
                        .withMaxToken(2048)
                        .withEnableThinking(false)
                        .withMultiModel("qwen3.7-flash-2026-07-15".equals(model))
                        .withInternalToolExecutionEnabled(false)
                        .build();
        if (!tools.isEmpty())
            options.setTools(
                    tools.stream()
                            .map(t -> mapper.convertValue(t, DashScopeApi.FunctionTool.class))
                            .toList());
        if (json)
            options.setResponseFormat(
                    new DashScopeResponseFormat(DashScopeResponseFormat.Type.JSON_OBJECT));
        if (!tools.isEmpty() && "PROCUREMENT".equals(agent) && round == 1)
            options.setToolChoice("required");
        return new Prompt(converted, options);
    }

    private JsonNode invoke(
            String turn,
            int round,
            String agent,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            boolean json,
            Consumer<String> consumer) {
        if (!available()) throw new ModelCallException("MODEL_DISABLED", "AI暂未启用");
        if (round < 0 || round >= 5)
            throw new IllegalArgumentException("Assistant request loop exceeded");
        if (Thread.currentThread().isInterrupted())
            throw new ModelCallException("MODEL_CANCELED", "已停止回复");
        var prompt = prompt(messages, tools, json, agent, round);
        ((DashScopeChatOptions) prompt.getOptions()).setIncrementalOutput(consumer != null);
        var state = new Running();
        if (running.putIfAbsent(turn, state) != null)
            throw new ModelCallException("MODEL_BUSY", "当前回复仍在处理中");
        var attempt =
                new AssistantUsageAdvisor.Attempt(
                        jdbc, turn, round, agent, model, state.canceled::get);
        try {
            // Use the same total deadline and cancellation for both call and stream.
            var responses =
                    consumer == null
                            ? reactor.core.publisher.Mono.fromCallable(
                                            () ->
                                                    chat.prompt(prompt)
                                                            .advisors(
                                                                    a ->
                                                                            a.param(
                                                                                    AssistantUsageAdvisor
                                                                                            .KEY,
                                                                                    attempt))
                                                            .call()
                                                            .chatResponse())
                                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                                    .flux()
                            : chat
                                    .prompt(prompt)
                                    .advisors(a -> a.param(AssistantUsageAdvisor.KEY, attempt))
                                    .stream()
                                    .chatResponse();
            responses
                    .takeUntilOther(state.stop.asMono())
                    .doOnNext(response -> state.accept(response, consumer))
                    .blockLast(timeout);
            if (state.canceled.get() || Thread.currentThread().isInterrupted())
                throw new ModelCallException("MODEL_CANCELED", "已停止回复");
            if (!Set.of("STOP", "TOOL_CALLS").contains(state.finish))
                throw new ModelCallException("MODEL_OUTPUT", "AI回复未完整结束，请重新提问");
            var result =
                    mapper.createObjectNode()
                            .put("role", "assistant")
                            .put("content", state.text.toString());
            if (!state.tools.isEmpty()) {
                var calls = result.putArray("tool_calls");
                for (var tool : state.tools.values()) {
                    var call = calls.addObject().put("id", tool.id()).put("type", "function");
                    call.putObject("function")
                            .put("name", tool.name())
                            .put("arguments", tool.arguments());
                }
            }
            return result;
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            org.slf4j.LoggerFactory.getLogger(DashScopeAssistantModel.class)
                    .warn(
                            "Assistant transport failed: turn={}, type={}, rootType={}",
                            turn,
                            e.getClass().getSimpleName(),
                            cause.getClass().getSimpleName());
            String code =
                    state.canceled.get() || Thread.currentThread().isInterrupted()
                            ? "MODEL_CANCELED"
                            : e instanceof ModelCallException m
                                    ? m.code()
                                    : e
                                                    instanceof
                                                    org.springframework.web.client
                                                                            .RestClientResponseException
                                                                    http
                                            ? "MODEL_HTTP_" + http.getStatusCode().value()
                                            : e
                                                            instanceof
                                                            org.springframework.web.reactive
                                                                                    .function.client
                                                                                    .WebClientResponseException
                                                                            http
                                                    ? "MODEL_HTTP_" + http.getStatusCode().value()
                                                    : "MODEL_NETWORK";
            attempt.failSafely(code);
            throw new ModelCallException(
                    code, e instanceof ModelCallException ? e.getMessage() : "AI请求未完成，没有自动重试");
        } finally {
            attempt.failSafely("MODEL_INTERRUPTED");
            state.canceled.set(true);
            running.remove(turn, state);
        }
    }

    private static class Running {
        final Sinks.Empty<Void> stop = Sinks.empty();
        final AtomicBoolean canceled = new AtomicBoolean();
        final StringBuilder text = new StringBuilder();
        final Map<String, AssistantMessage.ToolCall> tools = new LinkedHashMap<>();
        String finish = "";

        void accept(ChatResponse response, Consumer<String> consumer) {
            if (canceled.get()) return;
            var generation = response.getResult();
            if (generation == null) return;
            var reason = generation.getMetadata().getFinishReason();
            if (reason != null && !reason.isBlank() && !reason.equalsIgnoreCase("null"))
                finish = reason.toUpperCase(Locale.ROOT);
            var message = generation.getOutput();
            // DashScope's framework adapter assembles tool fragments before emitting tool calls.
            for (var tool : message.getToolCalls()) tools.put(tool.id(), tool);
            if (message.getText() != null && !message.getText().isEmpty()) {
                text.append(message.getText());
                if (consumer != null && !canceled.get()) consumer.accept(text.toString());
            }
        }
    }
}
