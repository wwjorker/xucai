package com.xucai.aigc.procurement.retrieval;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xucai.aigc.procurement.ModelCallException;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class SpringAiEmbeddingTransportTest {
    final ObjectMapper mapper = new ObjectMapper();

    String body(String index, boolean usage) {
        String vector = "1," + "0,".repeat(1022) + "0";
        return "{\"output\":{\"embeddings\":[{\"text_index\":"
                + index
                + ",\"embedding\":["
                + vector
                + "]}]}"
                + (usage ? ",\"usage\":{\"total_tokens\":8}" : "")
                + "}";
    }

    @Test
    void realFrameworkSends1024DenseAndBothTextTypes() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var wire = new AtomicReference<String>();
        var count = new AtomicInteger();
        server.createContext(
                "/api/v1/services/embeddings/text-embedding/text-embedding",
                e -> {
                    count.incrementAndGet();
                    wire.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] bytes = body("0", true).getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().set("Content-Type", "application/json");
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.start();
        try {
            var ledger = mock(EmbeddingLedger.class);
            var client =
                    new DashScopeEmbeddingClient(
                            ledger,
                            mapper,
                            SpringAiEmbeddingTransport.create(
                                    "local-test",
                                    mapper,
                                    "http://127.0.0.1:" + server.getAddress().getPort()));
            for (String kind : List.of("query", "document")) {
                String id = UUID.randomUUID() + ":0";
                var result = client.embed(id, kind, List.of("公开参数"));
                assertEquals(1024, result.get(0).length);
                assertEquals(1, result.get(0)[0]);
                var request = mapper.readTree(wire.get());
                assertEquals(kind, request.path("parameters").path("text_type").asText());
                assertEquals(1024, request.path("parameters").path("dimension").asInt());
                assertEquals("dense", request.path("parameters").path("output_type").asText());
                verify(ledger).finish(id, 8, null);
            }
            assertEquals(2, count.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingUsageIsRejectedInsteadOfInventingZero() throws Exception {
        assertRejected(body("0", false), "EMBEDDING_USAGE");
    }

    @Test
    void fractionalRowIndexCannotBeRoundedIntoValidIdentity() throws Exception {
        assertRejected(body("0.5", true), "EMBEDDING_UNAVAILABLE");
    }

    private void assertRejected(String response, String expected) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var count = new AtomicInteger();
        server.createContext(
                "/",
                e -> {
                    count.incrementAndGet();
                    e.getRequestBody().readAllBytes();
                    var bytes = response.getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().set("Content-Type", "application/json");
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.start();
        try {
            var ledger = mock(EmbeddingLedger.class);
            var client =
                    new DashScopeEmbeddingClient(
                            ledger,
                            mapper,
                            SpringAiEmbeddingTransport.create(
                                    "local-test",
                                    mapper,
                                    "http://127.0.0.1:" + server.getAddress().getPort()));
            var error =
                    assertThrows(
                            ModelCallException.class,
                            () -> client.embed(UUID.randomUUID() + ":0", "query", List.of("test")));
            assertEquals(expected, error.code());
            assertEquals(1, count.get());
        } finally {
            server.stop(0);
        }
    }
}
