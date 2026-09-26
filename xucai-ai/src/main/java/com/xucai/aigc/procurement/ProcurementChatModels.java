package com.xucai.aigc.procurement;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** Shared SDK transport policy. Callers retain their own ledgers and business limits. */
public final class ProcurementChatModels {
    private ProcurementChatModels() {}

    public static ChatModel create(
            String key, ObjectMapper mapper, String baseUrl, int byteLimit, Duration timeout) {
        var http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(timeout);
        var api =
                new DashScopeApi(
                        baseUrl,
                        new org.springframework.ai.model.SimpleApiKey(
                                key.isBlank() ? "disabled-in-slice-0" : key),
                        new org.springframework.util.LinkedMultiValueMap<>(),
                        null,
                        RestClient.builder().requestFactory(requests),
                        WebClient.builder().clientConnector(new JdkClientHttpConnector(http)),
                        new org.springframework.web.client.DefaultResponseErrorHandler()) {
                    private void bounded(ChatCompletionRequest request) {
                        try {
                            if (mapper.writeValueAsBytes(request).length > byteLimit)
                                throw new ModelCallException(
                                        "MODEL_INPUT_TOO_LONG", "这段对话较长，请新建对话继续");
                        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                            throw new ModelCallException("MODEL_INPUT", "消息无法编码");
                        }
                    }

                    @Override
                    public org.springframework.http.ResponseEntity<ChatCompletion>
                            chatCompletionEntity(ChatCompletionRequest request) {
                        bounded(request);
                        return super.chatCompletionEntity(request);
                    }

                    @Override
                    public reactor.core.publisher.Flux<ChatCompletionChunk> chatCompletionStream(
                            ChatCompletionRequest request) {
                        bounded(request);
                        return super.chatCompletionStream(request);
                    }
                };
        return DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(
                        DashScopeChatOptions.builder()
                                .withInternalToolExecutionEnabled(false)
                                .build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
    }
}
