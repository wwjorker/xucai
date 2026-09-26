package com.xucai.aigc.procurement;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Executable checks against the exact locally pinned framework, without cloud calls. */
class SpringAiCompatibilityTest {
    final ObjectMapper mapper = new ObjectMapper();
    final DashScopeApi api = mock(DashScopeApi.class);
    final ToolCallingManager manager = mock(ToolCallingManager.class);

    DashScopeChatModel chat() {
        return DashScopeChatModel.builder().dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder().withModel("qwen-plus-2025-12-01")
                        .withInternalToolExecutionEnabled(false).withEnableThinking(false).build())
                .toolCallingManager(manager).retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    }

    String response(String content, String finish, String tools, boolean usage) {
        return "{\"request_id\":\"test\",\"output\":{\"choices\":[{\"finish_reason\":\"" + finish
                + "\",\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\",\"tool_calls\":" + tools
                + "}}]}" + (usage ? ",\"usage\":{\"input_tokens\":10,\"output_tokens\":3}" : "") + "}";
    }

    @Test void frameworkReturnsToolCallsWithoutExecutingOrCallingModelAgain() throws Exception {
        String tools = "[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"set_cart_quantity\",\"arguments\":\"{}\"}}]";
        when(api.chatCompletionEntity(any())).thenReturn(ResponseEntity.ok(mapper.readValue(response("", "tool_calls", tools, true), DashScopeApi.ChatCompletion.class)));
        var result = chat().call(new Prompt("选择工具"));
        assertEquals("set_cart_quantity", result.getResult().getOutput().getToolCalls().get(0).name());
        verify(manager, never()).executeToolCalls(any(), any());
        verify(api, times(1)).chatCompletionEntity(any());
        assertEquals(10, result.getMetadata().getUsage().getPromptTokens());
    }

    @Test void explicitSingleAttemptPreventsAutomaticRetry() {
        when(api.chatCompletionEntity(any())).thenThrow(new IllegalStateException("simulated transport failure"));
        assertThrows(RuntimeException.class, () -> chat().call(new Prompt("hello")));
        verify(api, times(1)).chatCompletionEntity(any());
    }

    @Test void missingUsageRemainsDistinguishableFromActualZero() throws Exception {
        when(api.chatCompletionEntity(any())).thenReturn(ResponseEntity.ok(mapper.readValue(response("hello", "stop", "[]", false), DashScopeApi.ChatCompletion.class)));
        assertInstanceOf(EmptyUsage.class, chat().call(new Prompt("hello")).getMetadata().getUsage());
    }

    @Test void streamEmitsDeltasAndReportsUsageWithoutToolExecution() throws Exception {
        var first = mapper.readValue(response("你", "null", "[]", false), DashScopeApi.ChatCompletionChunk.class);
        var last = mapper.readValue(response("好", "stop", "[]", true), DashScopeApi.ChatCompletionChunk.class);
        when(api.chatCompletionStream(any())).thenReturn(Flux.just(first, last));
        var frames = chat().stream(new Prompt("hello")).collectList().block();
        assertNotNull(frames);
        assertEquals(List.of("你", "好"), frames.stream().map(r -> r.getResult().getOutput().getText()).toList());
        assertEquals(3, frames.get(frames.size()-1).getMetadata().getUsage().getCompletionTokens());
        verify(manager, never()).executeToolCalls(any(), any());
    }

    @Test void pinnedEmbeddingAdapterOmitsDimensionsDespiteConfiguredOption() throws Exception {
        // Documents the 1.0.0.2 compatibility gap. Migration must not silently assume this option is sent.
        when(api.embeddings(any())).thenReturn(ResponseEntity.ok(mapper.readValue(
                "{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":[0.1,0.2]}]},\"usage\":{\"total_tokens\":2}}",
                DashScopeApi.EmbeddingList.class)));
        var embedding = new DashScopeEmbeddingModel(api, MetadataMode.EMBED,
                DashScopeEmbeddingOptions.builder().withModel("text-embedding-v4").withDimensions(1024).withTextType("query").build(),
                RetryTemplate.builder().maxAttempts(1).build());
        embedding.call(new EmbeddingRequest(List.of("测试"), null));
        var capture = ArgumentCaptor.forClass(DashScopeApi.EmbeddingRequest.class);
        verify(api).embeddings(capture.capture());
        var request = mapper.valueToTree(capture.getValue());
        assertEquals("query", request.path("parameters").path("text_type").asText());
        assertFalse(request.path("parameters").has("dimension"));
        assertFalse(request.path("parameters").has("dimensions"));
    }
}
