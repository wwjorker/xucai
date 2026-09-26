package com.xucai.aigc.procurement.retrieval;

import com.fasterxml.jackson.databind.*;
import com.xucai.aigc.procurement.ModelCallException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
@Profile("xucai")
@ConditionalOnProperty(name = "xucai.procurement.retrieval.enabled", havingValue = "true")
public class DashScopeEmbeddingClient implements EmbeddingClient {
    static final URI ENDPOINT =
            URI.create(
                    "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding");
    private final EmbeddingLedger ledger;
    private final ObjectMapper mapper;
    private final Transport transport;

    record Response(int status, String body) {}

    @FunctionalInterface
    interface Transport {
        Response send(String body) throws Exception;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DashScopeEmbeddingClient(
            EmbeddingLedger ledger,
            ObjectMapper mapper,
            @Value("${xucai.ai.dashscope.key:}") String key) {
        this(ledger, mapper, transport(key));
    }

    DashScopeEmbeddingClient(EmbeddingLedger ledger, ObjectMapper mapper, Transport transport) {
        this.ledger = ledger;
        this.mapper =
                mapper.copy()
                        .enable(
                                com.fasterxml.jackson.core.JsonParser.Feature
                                        .STRICT_DUPLICATE_DETECTION);
        this.transport = transport;
    }

    private static Transport transport(String key) {
        if (key.isBlank()
                || key.equals("disabled-in-slice-0")
                || key.chars().anyMatch(Character::isWhitespace))
            throw new IllegalStateException("采购向量已启用但凭据未配置");
        return SpringAiEmbeddingTransport.create(
                key, new ObjectMapper(), "https://dashscope.aliyuncs.com");
    }

    @Override
    public List<float[]> embed(String callId, String kind, List<String> input) {
        return embed(callId, kind, input, false);
    }

    @Override
    public List<float[]> embedAssistant(String callId, String query) {
        return embed(callId, "query", List.of(query), true);
    }

    private List<float[]> embed(String callId, String kind, List<String> input, boolean personal) {
        List<String> texts = validate(callId, kind, input);
        int bytes = texts.stream().mapToInt(s -> s.getBytes(StandardCharsets.UTF_8).length).sum();
        String body;
        try {
            body =
                    mapper.writeValueAsString(
                            Map.of(
                                    "model",
                                    MODEL,
                                    "input",
                                    Map.of("texts", texts),
                                    "parameters",
                                    Map.of(
                                            "dimension",
                                            DIMENSIONS,
                                            "text_type",
                                            kind,
                                            "output_type",
                                            "dense")));
        } catch (Exception e) {
            throw error("EMBEDDING_INPUT");
        }
        if (Thread.currentThread().isInterrupted()) throw error("EMBEDDING_CANCELED");
        // 官方0.0005元/千token。按最多8192token/文本的模型上限预留，不依赖字符数估token或免费额度。
        if (personal) ledger.reserveAssistant(callId, bytes, 4096L);
        else ledger.reserve(callId, kind, texts.size(), bytes, 4096L * texts.size());
        Integer tokens = null;
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            var response = transport.send(body);
            if (response.status() != 200)
                throw error(
                        response.status() == 401 || response.status() == 403
                                ? "EMBEDDING_AUTH"
                                : response.status() == 429
                                        ? "EMBEDDING_RATE_LIMIT"
                                        : "EMBEDDING_HTTP");
            if (response.body() == null || response.body().length() > 1000000)
                throw error("EMBEDDING_OUTPUT");
            var json = mapper.readTree(response.body());
            var usage = json.path("usage").path("total_tokens");
            if (usage.isIntegralNumber()
                    && usage.canConvertToInt()
                    && usage.intValue() >= 0
                    && usage.intValue() <= 8192 * texts.size()) tokens = usage.intValue();
            else throw error("EMBEDDING_USAGE");
            var result = parse(json, texts.size());
            ledger.finish(callId, tokens, null);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed(callId, tokens, "EMBEDDING_CANCELED");
            throw error("EMBEDDING_CANCELED");
        } catch (ModelCallException e) {
            failed(callId, tokens, e.code());
            throw e;
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            org.slf4j.LoggerFactory.getLogger(DashScopeEmbeddingClient.class)
                    .warn(
                            "Embedding transport failed: call={}, type={}, rootType={}",
                            callId,
                            e.getClass().getSimpleName(),
                            cause.getClass().getSimpleName());
            failed(callId, tokens, "EMBEDDING_UNAVAILABLE");
            throw error("EMBEDDING_UNAVAILABLE");
        }
    }

    static List<String> validate(String id, String kind, List<String> texts) {
        if (id == null
                || !id.matches("[0-9a-f-]{36}:[0-9]{1,3}")
                || !("document".equals(kind) || "query".equals(kind))
                || texts == null
                || texts.isEmpty()
                || texts.size() > 10
                || "query".equals(kind) && texts.size() != 1) throw error("EMBEDDING_INPUT");
        for (var text : texts)
            if (text == null
                    || text.isBlank()
                    || text.getBytes(StandardCharsets.UTF_8).length > 4096)
                throw error("EMBEDDING_INPUT");
        return List.copyOf(texts);
    }

    static List<float[]> parse(JsonNode json, int expected) {
        if (json.has("code") && !json.path("code").asText().isBlank())
            throw error("EMBEDDING_OUTPUT");
        var rows = json.path("output").path("embeddings");
        if (!rows.isArray() || rows.size() != expected) throw error("EMBEDDING_OUTPUT");
        float[][] vectors = new float[expected][];
        for (var row : rows) {
            var idx = row.path("text_index");
            if (!idx.isIntegralNumber()
                    || !idx.canConvertToInt()
                    || idx.intValue() < 0
                    || idx.intValue() >= expected
                    || vectors[idx.intValue()] != null) throw error("EMBEDDING_OUTPUT");
            var values = row.path("embedding");
            if (!values.isArray() || values.size() != DIMENSIONS)
                throw error("EMBEDDING_DIMENSIONS");
            float[] vector = new float[DIMENSIONS];
            double norm = 0;
            for (int i = 0; i < DIMENSIONS; i++) {
                if (!values.get(i).isNumber()) throw error("EMBEDDING_OUTPUT");
                vector[i] = values.get(i).floatValue();
                if (!Float.isFinite(vector[i])) throw error("EMBEDDING_OUTPUT");
                norm += (double) vector[i] * vector[i];
            }
            if (norm == 0 || !Double.isFinite(norm)) throw error("EMBEDDING_OUTPUT");
            vectors[idx.intValue()] = vector;
        }
        return List.of(vectors);
    }

    private void failed(String id, Integer tokens, String error) {
        try {
            ledger.finish(id, tokens, error);
        } catch (RuntimeException ignored) {
            /*保留RESERVED，不释放预算，不重发。*/
        }
    }

    static ModelCallException error(String code) {
        return new ModelCallException(code, "向量服务未完成本次操作，未自动重试；原商品资料与采购业务不变");
    }
}
