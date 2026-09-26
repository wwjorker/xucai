package com.xucai.aigc.procurement.retrieval;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;

import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/** Compatibility shim for Alibaba 1.0.0.2: its parameter record omits dimension/output_type. */
final class SpringAiEmbeddingTransport {
    private SpringAiEmbeddingTransport() {}

    static DashScopeEmbeddingClient.Transport create(
            String key, ObjectMapper source, String baseUrl) {
        var mapper =
                source.copy()
                        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mapper.getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder().maxDocumentLength(1000000).build());
        var module = new SimpleModule();
        module.addSerializer(
                DashScopeApi.EmbeddingRequestInputParameters.class,
                new JsonSerializer<>() {
                    @Override
                    public void serialize(
                            DashScopeApi.EmbeddingRequestInputParameters value,
                            JsonGenerator json,
                            SerializerProvider provider)
                            throws java.io.IOException {
                        json.writeStartObject();
                        json.writeStringField("text_type", value.textType());
                        json.writeNumberField("dimension", EmbeddingClient.DIMENSIONS);
                        json.writeStringField("output_type", "dense");
                        json.writeEndObject();
                    }
                });
        mapper.registerModule(module);
        var http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(35));
        var rest =
                RestClient.builder()
                        .requestFactory(factory)
                        .messageConverters(
                                converters -> {
                                    converters.removeIf(
                                            c -> c instanceof MappingJackson2HttpMessageConverter);
                                    converters.add(new MappingJackson2HttpMessageConverter(mapper));
                                });
        var api =
                DashScopeApi.builder().apiKey(key).baseUrl(baseUrl).restClientBuilder(rest).build();
        var model =
                new DashScopeEmbeddingModel(
                        api,
                        MetadataMode.EMBED,
                        DashScopeEmbeddingOptions.builder()
                                .withModel(EmbeddingClient.MODEL)
                                .withDimensions(EmbeddingClient.DIMENSIONS)
                                .build(),
                        RetryTemplate.builder().maxAttempts(1).build());
        return body -> {
            var input = mapper.readTree(body);
            List<String> texts = new ArrayList<>();
            input.path("input").path("texts").forEach(t -> texts.add(t.asText()));
            var options =
                    DashScopeEmbeddingOptions.builder()
                            .withModel(EmbeddingClient.MODEL)
                            .withDimensions(EmbeddingClient.DIMENSIONS)
                            .withTextType(input.path("parameters").path("text_type").asText())
                            .build();
            try {
                var response =
                        reactor.core.publisher.Mono.fromCallable(
                                        () -> model.call(new EmbeddingRequest(texts, options)))
                                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                                .block(Duration.ofSeconds(35));
                if (response == null) throw DashScopeEmbeddingClient.error("EMBEDDING_OUTPUT");
                var output = mapper.createObjectNode();
                var rows = output.putObject("output").putArray("embeddings");
                for (var row : response.getResults()) {
                    var item = rows.addObject().put("text_index", row.getIndex());
                    item.set("embedding", mapper.valueToTree(row.getOutput()));
                }
                var usage = response.getMetadata().getUsage();
                // 1.0.0.2 does not override EmbeddingUsage.getTotalTokens(); its computed value is
                // always zero.
                // DefaultUsage retains the native record, whose totalTokens is the actual provider
                // value.
                if (usage != null
                        && !(usage instanceof EmptyUsage)
                        && usage.getNativeUsage() instanceof DashScopeApi.EmbeddingUsage nativeUsage
                        && nativeUsage.totalTokens() != null)
                    output.putObject("usage").put("total_tokens", nativeUsage.totalTokens());
                // The existing validator still enforces row identity, dimensions, finite values and
                // nonzero norm.
                return new DashScopeEmbeddingClient.Response(
                        200, mapper.writeValueAsString(output));
            } catch (RestClientResponseException e) {
                return new DashScopeEmbeddingClient.Response(e.getStatusCode().value(), "");
            }
        };
    }
}
