package com.xucai.aigc.procurement.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xucai.aigc.procurement.ModelCallException;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real framework serialization/HTTP/SSE on loopback, no provider or API key. */
class AssistantSpringAiWireTest {
    @Test
    void nativeRequestUsesCorrectEndpointAndReturnsUsage() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var body = new AtomicReference<String>();
        var mapper = new ObjectMapper();
        server.createContext(
                "/api/v1/services/aigc/multimodal-generation/generation",
                exchange -> {
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] response =
                            "{\"request_id\":\"local\",\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"ok\"}]}}]},\"usage\":{\"input_tokens\":7,\"output_tokens\":2}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            var jdbc = mock(JdbcTemplate.class);
            var chat =
                    DashScopeAssistantModel.createChat(
                            "local-test",
                            mapper,
                            "http://127.0.0.1:" + server.getAddress().getPort());
            var model =
                    new DashScopeAssistantModel(
                            jdbc,
                            mapper,
                            "local-test",
                            "qwen3.7-flash-2026-07-15",
                            true,
                            chat,
                            Duration.ofSeconds(5));
            assertEquals(
                    "ok",
                    model.call(
                                    "wire",
                                    0,
                                    "ROUTER",
                                    List.of(Map.of("role", "user", "content", "test")),
                                    List.of(),
                                    true)
                            .path("content")
                            .asText());
            var request = mapper.readTree(body.get());
            assertFalse(request.path("parameters").path("enable_thinking").asBoolean());
            assertFalse(request.path("parameters").path("incremental_output").asBoolean());
            assertEquals(
                    "json_object",
                    request.path("parameters").path("response_format").path("type").asText());
            verify(jdbc)
                    .update(
                            startsWith("UPDATE assistant_model_call SET status='DONE'"),
                            eq(7),
                            eq(2),
                            any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oversizedUtf8RequestNeverReachesNetwork() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext(
                "/",
                e -> {
                    calls.incrementAndGet();
                    e.sendResponseHeaders(500, -1);
                    e.close();
                });
        server.start();
        try {
            var mapper = new ObjectMapper();
            var jdbc = mock(JdbcTemplate.class);
            var chat =
                    DashScopeAssistantModel.createChat(
                            "local-test",
                            mapper,
                            "http://127.0.0.1:" + server.getAddress().getPort());
            var model =
                    new DashScopeAssistantModel(
                            jdbc,
                            mapper,
                            "local-test",
                            "qwen3.7-flash-2026-07-15",
                            true,
                            chat,
                            Duration.ofSeconds(5));
            var error =
                    assertThrows(
                            ModelCallException.class,
                            () ->
                                    model.call(
                                            "large",
                                            0,
                                            "GENERAL",
                                            List.of(
                                                    Map.of(
                                                            "role",
                                                            "user",
                                                            "content",
                                                            "中".repeat(21000))),
                                            List.of(),
                                            false));
            assertEquals("MODEL_INPUT_TOO_LONG", error.code());
            assertEquals(0, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void realSseFramesAreDecodedIncrementallyByFramework() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    e.getRequestBody().readAllBytes();
                    e.getResponseHeaders().set("Content-Type", "text/event-stream");
                    e.sendResponseHeaders(200, 0);
                    String template =
                            "{\"request_id\":\"sse\",\"output\":{\"choices\":[{\"finish_reason\":\"%s\",\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"%s\"}]}}]}}";
                    for (var part :
                            List.of(new String[] {"null", "你"}, new String[] {"stop", "好"})) {
                        e.getResponseBody()
                                .write(
                                        ("data: " + template.formatted(part[0], part[1]) + "\n\n")
                                                .getBytes(StandardCharsets.UTF_8));
                        e.getResponseBody().flush();
                    }
                    e.close();
                });
        server.start();
        try {
            var mapper = new ObjectMapper();
            var jdbc = mock(JdbcTemplate.class);
            var chat =
                    DashScopeAssistantModel.createChat(
                            "local-test",
                            mapper,
                            "http://127.0.0.1:" + server.getAddress().getPort());
            var model =
                    new DashScopeAssistantModel(
                            jdbc,
                            mapper,
                            "local-test",
                            "qwen3.7-flash-2026-07-15",
                            true,
                            chat,
                            Duration.ofSeconds(5));
            List<String> frames = new ArrayList<>();
            assertEquals(
                    "你好",
                    model.stream(
                                    "sse",
                                    1,
                                    "GENERAL",
                                    List.of(Map.of("role", "user", "content", "hi")),
                                    List.of(),
                                    frames::add)
                            .path("content")
                            .asText());
            assertEquals(List.of("你", "你好"), frames);
        } finally {
            server.stop(0);
        }
    }
}
