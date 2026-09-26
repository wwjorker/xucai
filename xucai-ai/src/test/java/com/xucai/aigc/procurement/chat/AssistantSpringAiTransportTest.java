package com.xucai.aigc.procurement.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.aigc.procurement.ModelCallException;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.jdbc.core.JdbcTemplate;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

class AssistantSpringAiTransportTest {
    final ChatModel chat = mock(ChatModel.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final DashScopeAssistantModel model =
            new DashScopeAssistantModel(
                    jdbc,
                    new ObjectMapper(),
                    "test",
                    "qwen-plus-2025-12-01",
                    true,
                    chat,
                    Duration.ofMillis(300));
    final List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "你好"));

    ChatResponse frame(String text, String reason) {
        return new ChatResponse(
                List.of(
                        new Generation(
                                new AssistantMessage(text),
                                ChatGenerationMetadata.builder().finishReason(reason).build())));
    }

    @Test
    void deltasAreCumulativeAndLedgerPrecedesTransport() {
        when(chat.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenAnswer(
                        i -> {
                            verify(jdbc)
                                    .update(
                                            startsWith("INSERT"),
                                            any(),
                                            eq("turn"),
                                            eq(0),
                                            eq("GENERAL"),
                                            any());
                            return Flux.just(frame("你", ""), frame("好", "STOP"));
                        });
        List<String> frames = new ArrayList<>();
        var result = model.stream("turn", 0, "GENERAL", messages, List.of(), frames::add);
        assertEquals(List.of("你", "你好"), frames);
        assertEquals("你好", result.path("content").asText());
        verify(jdbc)
                .update(
                        startsWith("UPDATE assistant_model_call SET status='DONE'"),
                        isNull(),
                        isNull(),
                        any());
    }

    @Test
    void cancelTerminatesUpstreamAndRejectsPartialSuccess() {
        AtomicBoolean disposed = new AtomicBoolean();
        when(chat.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(
                        Flux.concat(Flux.just(frame("部分", "")), Flux.never())
                                .doOnCancel(() -> disposed.set(true)));
        var error =
                assertThrows(
                        ModelCallException.class,
                        () ->
                                model.stream(
                                        "cancel",
                                        0,
                                        "GENERAL",
                                        messages,
                                        List.of(),
                                        s -> model.cancel("cancel")));
        assertEquals("MODEL_CANCELED", error.code());
        assertTrue(disposed.get());
        verify(jdbc, never())
                .update(
                        startsWith("UPDATE assistant_model_call SET status='DONE'"),
                        any(),
                        any(),
                        any());
    }

    @Test
    void incompleteStreamFailsWithoutRetry() {
        when(chat.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.just(frame("残缺", "")));
        assertEquals(
                "MODEL_OUTPUT",
                assertThrows(
                                ModelCallException.class,
                                () ->
                                        model.stream(
                                                "broken", 0, "GENERAL", messages, List.of(),
                                                s -> {}))
                        .code());
        verify(chat, times(1)).stream(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test
    void deadlineCancelsEvenContinuouslyEmittingStream() {
        AtomicBoolean disposed = new AtomicBoolean();
        when(chat.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(
                        Flux.interval(Duration.ofMillis(10))
                                .map(i -> frame("字", ""))
                                .doOnCancel(() -> disposed.set(true)));
        assertThrows(
                ModelCallException.class,
                () -> model.stream("timeout", 0, "GENERAL", messages, List.of(), s -> {}));
        assertTrue(disposed.get());
        verify(chat, times(1)).stream(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test
    void modelFamilySelectsNativeEndpointAndPlainRequestsOmitTools() {
        var flash =
                new DashScopeAssistantModel(
                        jdbc,
                        new ObjectMapper(),
                        "test",
                        "qwen3.7-flash-2026-07-15",
                        true,
                        chat,
                        Duration.ofSeconds(1));
        var flashOptions =
                (DashScopeChatOptions)
                        flash.prompt(messages, List.of(), true, "ROUTER", 0).getOptions();
        assertTrue(flashOptions.getMultiModel());
        assertTrue(flashOptions.getTools() == null || flashOptions.getTools().isEmpty());
        var plusOptions =
                (DashScopeChatOptions)
                        model.prompt(messages, List.of(), true, "ROUTER", 0).getOptions();
        assertFalse(plusOptions.getMultiModel());
    }

    @Test
    void toolResultsKeepIdentityAndDisableInternalExecution() {
        var conversation =
                List.<Map<String, Object>>of(
                        messages.get(0),
                        Map.of(
                                "role",
                                "assistant",
                                "content",
                                "",
                                "tool_calls",
                                List.of(
                                        Map.of(
                                                "id",
                                                "call-7",
                                                "function",
                                                Map.of("name", "read_cart", "arguments", "{}")))),
                        Map.of(
                                "role",
                                "tool",
                                "tool_call_id",
                                "call-7",
                                "content",
                                "{\"items\":[]}"));
        var prompt =
                model.prompt(
                        conversation,
                        List.of(
                                Map.of(
                                        "type",
                                        "function",
                                        "function",
                                        Map.of(
                                                "name",
                                                "read_cart",
                                                "description",
                                                "read",
                                                "parameters",
                                                Map.of("type", "object")))),
                        false,
                        "PROCUREMENT",
                        1);
        var options = (DashScopeChatOptions) prompt.getOptions();
        assertEquals(false, options.getInternalToolExecutionEnabled());
        assertEquals("required", options.getToolChoice());
        var tool = (ToolResponseMessage) prompt.getInstructions().get(2);
        assertEquals("read_cart", tool.getResponses().get(0).name());
        assertEquals("call-7", tool.getResponses().get(0).id());
        // ChatClient may read default options during construction, but must not send a request.
        verify(chat, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        verify(chat, never()).stream(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test
    void syncToolCallReturnsToOrchestratorWithoutExecution() {
        var message =
                new AssistantMessage(
                        "",
                        Map.of(),
                        List.of(new AssistantMessage.ToolCall("a", "function", "read_cart", "{}")));
        when(chat.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(
                        new ChatResponse(
                                List.of(
                                        new Generation(
                                                message,
                                                ChatGenerationMetadata.builder()
                                                        .finishReason("TOOL_CALLS")
                                                        .build()))));
        var result = model.call("sync", 1, "PROCUREMENT", messages, List.of(), false);
        assertEquals(
                "read_cart",
                result.path("tool_calls").path(0).path("function").path("name").asText());
        verify(chat, times(1)).call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }
}
