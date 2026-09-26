package com.xucai.aigc.procurement.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;

import java.util.*;

class AssistantToolCatalogTest {
    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rolesExposeOnlyAllowedToolsAndNeverIdentityParameters() {
        var tools = new AssistantTools(null, null, mapper, null);
        for (var role : AssistantTools.ALLOWED.entrySet()) {
            var definitions = tools.definitions(role.getKey());
            var names = new HashSet<String>();
            for (var definition : definitions) {
                var function = mapper.valueToTree(definition).path("function");
                names.add(function.path("name").asText());
                var properties = function.path("parameters").path("properties");
                for (String key : List.of("context", "user", "userId", "agent", "productId"))
                    assertFalse(properties.has(key), key);
            }
            assertEquals(role.getValue(), names);
        }
        assertTrue(tools.definitions("UNKNOWN").isEmpty());
    }

    @Test
    void generatedSchemaPreservesQuantityAndDraftContracts() {
        var tools = new AssistantTools(null, null, mapper, null);
        for (var d : tools.definitions("PROCUREMENT")) {
            var f = mapper.valueToTree(d).path("function");
            var p = f.path("parameters").path("properties");
            switch (f.path("name").asText()) {
                case "set_cart_quantity" -> {
                    assertTrue(p.has("position"));
                    assertTrue(p.has("quantity"));
                    assertFalse(p.has("delta"));
                }
                case "prepare_requirement" -> {
                    assertEquals(6, p.path("items").path("maxItems").asInt());
                    assertTrue(p.path("items").path("items").path("properties").has("categoryId"));
                }
                case "ask_clarification" -> assertEquals(4, p.path("reason").path("enum").size());
            }
        }
    }

    @Test
    void frameworkCallbackCannotExecuteWithoutTrustedContext() {
        var executor = mock(AssistantTools.class);
        var catalog = new AssistantToolCatalog(executor, mapper);
        var callback =
                Arrays.stream(ToolCallbacks.from(catalog))
                        .filter(c -> c.getToolDefinition().name().equals("read_cart"))
                        .findFirst()
                        .orElseThrow();
        assertThrows(RuntimeException.class, () -> callback.call("{}"));
        verifyNoInteractions(executor);
    }

    @Test
    void annotatedEntryDelegatesToTheSameGuardedExecutor() {
        var executor = mock(AssistantTools.class);
        var catalog = new AssistantToolCatalog(executor, mapper);
        var context = new AssistantTools.Context(10003, List.of());
        catalog.set_cart_quantity(
                2,
                4,
                new ToolContext(Map.of("xucai.agent", "PROCUREMENT", "xucai.context", context)));
        verify(executor)
                .execute(
                        eq("PROCUREMENT"),
                        eq("set_cart_quantity"),
                        eq(mapper.createObjectNode().put("position", 2).put("quantity", 4)),
                        same(context));
    }
}
