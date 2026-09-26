package com.xucai.aigc.procurement.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.*;

class AssistantConversationContextTest {
    @Test void knowledgeKeepsUserReferencesButNeverOldProductProse() throws Exception {
        var messages = new ArrayList<Map<String,Object>>();
        AssistantConversationContext.append(messages,"KNOWLEDGE",
                List.of(Map.of("role","user","content","比较M100和M185"),
                        Map.of("role","assistant","content","M100旧规格和旧价格")),
                List.of(),"第二款的参数",new AssistantTools.Context(10003L,List.of()),
                null,null,new ObjectMapper());
        assertTrue(messages.toString().contains("比较M100和M185"));
        assertFalse(messages.toString().contains("M100旧规格和旧价格"));
        assertTrue(messages.stream().noneMatch(m -> "assistant".equals(m.get("role"))));
        assertEquals(List.of("第二款的参数"),messages.stream()
                .filter(m -> "user".equals(m.get("role"))).map(m -> m.get("content")).toList());
    }
    @Test
    void shoppingUsesHistoryAsDataAndKeepsUnknownSnapshotAndBudget() throws Exception {
        var messages = new ArrayList<Map<String, Object>>();
        var context = new AssistantTools.Context(10003L, List.of());
        context.budgetCents = 20000L;
        AssistantConversationContext.append(
                messages,
                "SHOPPING",
                List.of(
                        Map.of("role", "user", "content", "以前的条件"),
                        Map.of("role", "assistant", "content", "旧清单已有四件")),
                List.of(),
                "当前问题",
                context,
                Map.of("available", false),
                List.of(),
                new ObjectMapper());
        var text = messages.toString();
        assertFalse(text.contains("旧清单已有四件"));
        assertTrue(text.contains("以前的条件"));
        assertTrue(text.contains("200.00"));
        assertTrue(text.contains("\"available\":false"));
        assertEquals(
                List.of("当前问题"),
                messages.stream()
                        .filter(m -> "user".equals(m.get("role")))
                        .map(m -> m.get("content"))
                        .toList());
    }

    @Test
    void otherRolesKeepConversationWithoutShoppingFacts() throws Exception {
        var messages = new ArrayList<Map<String, Object>>();
        AssistantConversationContext.append(
                messages,
                "GENERAL",
                List.of(Map.of("role", "assistant", "content", "上次介绍")),
                List.of(),
                "再说说",
                new AssistantTools.Context(10003L, List.of()),
                null,
                null,
                new ObjectMapper());
        assertTrue(messages.toString().contains("上次介绍"));
        assertFalse(messages.toString().contains("当前采购清单快照"));
    }
}
