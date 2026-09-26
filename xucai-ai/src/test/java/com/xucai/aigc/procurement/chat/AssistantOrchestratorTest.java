package com.xucai.aigc.procurement.chat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantOrchestratorTest {
    @Test void semanticCartRevisionPrecedesUnrelatedConnectionValidation() throws Exception {
        turn=new AssistantSessionStore.Turn(tid,sid,2,"算了，改成2件","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,turn.text())).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        var route=mapper.createObjectNode().put("agent","SHOPPING");
        route.set("connectionChange",mapper.createObjectNode().put("connection","INVALID"));
        route.set("cartRequest",mapper.createObjectNode().put("action","REVISE").put("source",turn.text()));
        when(model.call(eq(tid),eq(0),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content",mapper.writeValueAsString(route)));
        when(tools.handleCartRequest(any(),any())).thenAnswer(i->{AssistantTools.Context c=i.getArgument(1);c.clarification="准备改为2件，请确认。";return true;});
        try {
            service.send(7,sid,tid,turn.text());
            verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),contains("准备改为2件"),any(),isNull());
            verify(tools,never()).execute(anyString(),anyString(),any(),any());
        } finally {service.close();}
    }
    @Test void advertisedConfirmationUsesStateHandlerBeforeRouter() throws Exception {
        turn=new AssistantSessionStore.Turn(tid,sid,2,"确认","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,turn.text())).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        when(tools.handleCartRequest(any(),any())).thenAnswer(i->{AssistantTools.Context c=i.getArgument(1);c.clarification="没有待确认的清单操作。";return true;});
        try {
            service.send(7,sid,tid,turn.text());
            verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),contains("没有待确认"),any(),isNull());
            verify(tools).handleCartRequest(argThat(r->r.path("action").asText().equals("CONFIRM")),any());
            verify(model).available();
            verifyNoMoreInteractions(model);
        } finally {service.close();}
    }
    @Test void fullyAuthorizedCardCommandWinsContradictoryCriteriaLabel() throws Exception {
        turn=new AssistantSessionStore.Turn(tid,sid,2,"第一款要4件","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,turn.text())).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        var previous=new AssistantSessionStore.Turn("previous",sid,1,"推荐鼠标","DONE","候选",mapper.readTree("{\"cardReferenceVersion\":1,\"products\":[{\"id\":\"2\",\"name\":\"M100\"}]}"),null,"now","now");
        when(store.turns(7,sid)).thenReturn(List.of(previous,turn));
        when(model.call(eq(tid),eq(0),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"agent\":\"SHOPPING\",\"action\":\"OTHER\",\"budgetChange\":\"UPDATE\",\"quantityTarget\":\"CRITERIA\",\"connectionChange\":{\"connection\":\"KEEP\"}}"));
        when(tools.execute(eq("PROCUREMENT"),eq("set_cart_quantity"),any(),any())).thenAnswer(i->{AssistantTools.Context c=i.getArgument(3);c.cartChanged=true;c.cartChanges.add("M100：0 → 4 件");return Map.of("changed",true);});
        try {
            service.send(7,sid,tid,turn.text());
            verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),contains("0 → 4"),any(),isNull());
            verify(tools).execute(eq("PROCUREMENT"),eq("set_cart_quantity"),argThat(v->v.path("position").asInt()==1&&v.path("quantity").asInt()==4),any());
            verify(tools,never()).execute(eq("SHOPPING"),anyString(),any(),any());
        } finally {service.close();}
    }
    @Test void cardQuantityCommandIgnoresUnrelatedMalformedConnectionChange() throws Exception {
        turn=new AssistantSessionStore.Turn(tid,sid,2,"第一款要4件","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,turn.text())).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        var previous=new AssistantSessionStore.Turn("previous",sid,1,"推荐鼠标","DONE","候选",mapper.readTree("{\"cardReferenceVersion\":1,\"products\":[{\"id\":\"2\",\"name\":\"M100\"}]}"),null,"now","now");
        when(store.turns(7,sid)).thenReturn(List.of(previous,turn));
        when(model.call(eq(tid),eq(0),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"agent\":\"SHOPPING\",\"action\":\"OTHER\",\"budgetChange\":\"UPDATE\",\"quantityTarget\":\"CRITERIA\",\"connectionChange\":{\"connection\":\"FAST\"}}"));
        when(tools.execute(eq("PROCUREMENT"),eq("set_cart_quantity"),any(),any())).thenAnswer(i->{AssistantTools.Context c=i.getArgument(3);c.cartChanged=true;c.cartChanges.add("M100：0 → 4 件");return Map.of("changed",true);});
        try {
            service.send(7,sid,tid,turn.text());
            verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),contains("0 → 4"),any(),isNull());
            verify(tools).execute(eq("PROCUREMENT"),eq("set_cart_quantity"),argThat(v->v.path("position").asInt()==1&&v.path("quantity").asInt()==4),any());
            verify(tools,never()).execute(eq("SHOPPING"),anyString(),any(),any());
        } finally {service.close();}
    }
    @Test void quantityCriteriaUpdateRefreshesCardsWithoutEnteringWriteTools() throws Exception {
        turn=new AssistantSessionStore.Turn(tid,sid,2,"改成两个鼠标，预算不变","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,turn.text())).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        var scope=new AssistantPurchaseScope(10000,0,false,Map.of("38",2));
        var previous=new AssistantSessionStore.Turn("previous",sid,1,"预算100以内推荐鼠标","DONE","候选",mapper.valueToTree(Map.of("purchaseScope",scope.snapshot())),null,"now","now");
        when(store.turns(7,sid)).thenReturn(List.of(previous,turn));
        var route=Map.of("agent","PROCUREMENT","action","OTHER","budgetChange","UPDATE","quantityTarget","CRITERIA","purchaseQuantity",2,"quantitySource","两个","connectionChange",Map.of("connection","KEEP"));
        when(model.call(eq(tid),eq(0),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content",mapper.writeValueAsString(route)));
        when(model.call(eq(tid),eq(1),anyString(),anyList(),eq(List.of()),eq(false))).thenReturn(mapper.createObjectNode().put("content","已按两件总价重新筛选。"));
        when(tools.execute(eq("SHOPPING"),eq("search_products"),any(),any())).thenReturn(Map.of("cards",List.of()));
        try {
            service.send(7,sid,tid,turn.text());
            verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),anyString(),argThat(r->mapper.valueToTree(r).path("purchaseScope").path("quantity").asInt()==2),isNull());
            verify(tools).execute(eq("SHOPPING"),eq("search_products"),any(),argThat(c->c.purchaseScope.chargeable(38,2)==0));
            verify(tools,never()).execute(eq("PROCUREMENT"),anyString(),any(),any());
        } finally {service.close();}
    }
    @Test void explicitKnowledgeReferenceLoadsEvidenceEvenWhenModelWouldSkipTools() throws Exception {
        turn=new AssistantSessionStore.Turn(tid,sid,2,"我想要了解一下第二款的参数","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,turn.text())).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        var result=mapper.readTree("{\"cardReferenceVersion\":1,\"products\":[{\"id\":\"1\",\"name\":\"M100\"},{\"id\":\"2\",\"name\":\"M185\"}]}");
        var previous=new AssistantSessionStore.Turn("previous",sid,1,"比较M100和M185","DONE","旧参数",result,null,"now","now");
        when(store.turns(7,sid)).thenReturn(List.of(previous,turn));
        when(model.call(eq(tid),eq(0),eq("ROUTER"),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"agent\":\"KNOWLEDGE\",\"action\":\"OTHER\"}"));
        when(tools.execute(eq("KNOWLEDGE"),eq("read_product"),any(),any())).thenAnswer(i->{AssistantTools.Context c=i.getArgument(3);c.retrievals.add(Map.of("productId",2));return Map.of("product","M185","evidence","当前资料");});
        when(model.call(eq(tid),eq(1),eq("KNOWLEDGE"),anyList(),eq(List.of()),eq(false))).thenReturn(mapper.createObjectNode().put("content","M185当前参数。"));
        try {
            service.send(7,sid,tid,turn.text());
            verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),anyString(),any(),isNull());
            verify(tools).execute(eq("KNOWLEDGE"),eq("read_product"),argThat(a->a.path("position").asInt()==2),any());
            verify(tools,never()).execute(eq("PROCUREMENT"),anyString(),any(),any());
        } finally {service.close();}
    }
    @Test void askingToUnderstandAnOptionCannotOverrideKnowledgeRoute() throws Exception {
        turn=new AssistantSessionStore.Turn(tid,sid,1,"我想要了解一下第二款的参数","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,turn.text())).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        when(model.call(eq(tid),eq(0),eq("ROUTER"),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"agent\":\"KNOWLEDGE\",\"action\":\"OTHER\"}"));
        when(model.call(eq(tid),eq(1),eq("KNOWLEDGE"),anyList(),anyList(),eq(false))).thenReturn(mapper.readTree("{\"tool_calls\":[{\"id\":\"e\",\"function\":{\"name\":\"read_evidence\",\"arguments\":\"{\\\"position\\\":2,\\\"query\\\":\\\"参数\\\"}\"}}]}"));
        when(tools.execute(eq("KNOWLEDGE"),eq("read_evidence"),any(),any())).thenReturn(Map.of("ready",true));
        when(model.call(eq(tid),eq(2),eq("KNOWLEDGE"),anyList(),anyList(),eq(false))).thenReturn(mapper.createObjectNode().put("content","第二款的参数介绍。"));
        try {
            service.send(7,sid,tid,turn.text());
            verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),eq("第二款的参数介绍。"),any(),isNull());
            verify(tools,never()).execute(eq("PROCUREMENT"),anyString(),any(),any());
        } finally {service.close();}
    }
    @Test void comparisonReturnsVerifiedFactsWithoutAnotherGenerationLoop() throws Exception {
        turn=new AssistantSessionStore.Turn(tid,sid,1,"M330和M185有什么区别","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,turn.text())).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        when(model.call(eq(tid),eq(0),eq("ROUTER"),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"agent\":\"KNOWLEDGE\",\"action\":\"OTHER\",\"comparisonKeywords\":[\"M330\",\"M185\"]}"));
        when(tools.execute(eq("KNOWLEDGE"),eq("compare_products"),any(),any())).thenAnswer(i->{AssistantTools.Context c=i.getArgument(3);c.comparisonText="两款的真实规格对照。";return Map.of("complete",true);});
        try {
            service.send(7,sid,tid,turn.text());
            verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),eq("两款的真实规格对照。"),any(),isNull());
            verify(tools,times(1)).execute(anyString(),anyString(),any(),any());
            verify(model,never()).stream(anyString(),anyInt(),anyString(),anyList(),anyList(),any());
        } finally {service.close();}
    }
    @Test void generalNumberedExplanationDoesNotTurnIntoOldProductCards() throws Exception {
        turn=new AssistantSessionStore.Turn(tid,sid,2,"USB接收器是什么意思","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,turn.text())).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        var result=mapper.createObjectNode().put("cardReferenceVersion",1);
        result.set("products",mapper.valueToTree(List.of(Map.of("id","36","name","M720","price",299))));
        var prior=new AssistantSessionStore.Turn("prior",sid,1,"找M720","DONE","选项1：M720",result,null,"now","now");
        when(store.turns(7,sid)).thenReturn(List.of(prior,turn));
        when(model.call(eq(tid),eq(0),eq("ROUTER"),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"agent\":\"GENERAL\",\"action\":\"OTHER\"}"));
        String answer="1. 接收器插在电脑USB口。\n2. 鼠标与接收器之间无线通信。";
        when(model.call(eq(tid),eq(1),eq("GENERAL"),anyList(),anyList(),eq(false))).thenReturn(mapper.createObjectNode().put("content",answer));
        try {
            service.send(7,sid,tid,turn.text());
            verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),eq(answer),any(),isNull());
        } finally {service.close();}
    }

    @Test void namedPurchaseWithoutCardsSearchesInsteadOfGuessingCartPosition() throws Exception {
        turn=new AssistantSessionStore.Turn(tid,sid,1,"我想买Logitech M720两件","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,turn.text())).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        var route=mapper.createObjectNode().put("agent","PROCUREMENT").put("action","OTHER").put("shoppingMode","SEARCH").put("productQuery","M720");
        route.set("connectionChange",mapper.createObjectNode().put("connection","KEEP"));
        when(model.call(eq(tid),eq(0),eq("ROUTER"),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content",route.toString()));
        when(tools.execute(eq("SHOPPING"),eq("search_products"),any(),any())).thenReturn(Map.of("products",List.of()));
        when(model.call(eq(tid),eq(1),eq("SHOPPING"),anyList(),eq(List.of()),eq(false))).thenReturn(mapper.createObjectNode().put("content","这是查到的M720，请确认后加入清单。"));
        try {
            service.send(7,sid,tid,turn.text());
            verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),anyString(),any(),isNull());
            verify(tools).execute(eq("SHOPPING"),eq("search_products"),argThat(a->a.path("keyword").asText().equals("M720")),any());
            verify(tools,never()).execute(eq("PROCUREMENT"),anyString(),any(),any());
        } finally {service.close();}
    }

    @Test void vagueFollowupCannotReadCartWithoutCartSubject() throws Exception {
        turn=new AssistantSessionStore.Turn(tid,sid,1,"给我介绍下详细一点","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,turn.text())).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        var route=mapper.createObjectNode().put("agent","PROCUREMENT").put("action","CART_DETAILS").put("productQuery","");
        route.set("connectionChange",mapper.createObjectNode().put("connection","KEEP"));
        when(model.call(eq(tid),eq(0),eq("ROUTER"),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content",route.toString()));
        when(model.call(eq(tid),eq(1),eq("GENERAL"),anyList(),anyList(),eq(false))).thenReturn(mapper.createObjectNode().put("content","你想进一步了解哪件设备？"));
        try {
            service.send(7,sid,tid,turn.text());
            verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),anyString(),any(),isNull());
            verify(tools,never()).shoppingSnapshot(any());
            verify(tools,never()).execute(anyString(),anyString(),any(),any());
        } finally {service.close();}
    }

    @Test void describeCartUsesReadOnlySnapshotThenAnswersWithoutToolLoop() throws Exception {
        turn=new AssistantSessionStore.Turn(tid,sid,1,"详细介绍下我采购清单里面的商品","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,turn.text())).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        var route=mapper.createObjectNode().put("agent","PROCUREMENT").put("action","CART_DETAILS").put("shoppingMode","CLARIFY");
        route.set("connectionChange",mapper.createObjectNode().put("connection","KEEP"));
        when(model.call(eq(tid),eq(0),eq("ROUTER"),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content",route.toString()));
        when(tools.shoppingSnapshot(any())).thenAnswer(i->{AssistantTools.Context c=i.getArgument(0);assertTrue(c.describingCart);c.shoppingCartSnapshot=Map.of("available",true,"items",List.of(Map.of("name","K780","quantity",2)));return c.shoppingCartSnapshot;});
        when(model.call(eq(tid),eq(1),eq("SHOPPING"),anyList(),eq(List.of()),eq(false))).thenReturn(mapper.createObjectNode().put("content","K780的资料说明。"));
        try {
            service.send(7,sid,tid,turn.text());
            verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),eq("K780的资料说明。"),any(),isNull());
            verify(tools,never()).execute(anyString(),anyString(),any(),any());
            verify(model).stream(eq(tid),eq(1),eq("SHOPPING"),anyList(),eq(List.of()),any());
        } finally {service.close();}
    }

    @Test void firstRecommendationPersistsConnectionBeforeSearchWithoutCartMutation() throws Exception {
        turn=new AssistantSessionStore.Turn(tid,sid,1,"预算200元，买4个USB有线鼠标，用于办公室日常文档处理。请推荐，不要加入清单。","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,turn.text())).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        var change=mapper.createObjectNode().put("category","鼠标").put("connection","WIRED").put("source","USB有线鼠标");
        var route=mapper.createObjectNode().put("agent","SHOPPING").put("action","OTHER").put("shoppingMode","SEARCH");
        route.set("connectionChange",change);
        when(model.call(eq(tid),eq(0),eq("ROUTER"),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content",route.toString()));
        when(tools.execute(eq("SHOPPING"),eq("search_products"),any(),any())).thenReturn(Map.of("cards",List.of()));
        when(model.call(eq(tid),eq(1),eq("SHOPPING"),anyList(),anyList(),eq(false))).thenReturn(mapper.createObjectNode().put("content","本次按有线条件查询，清单未修改。"));
        try {
            service.send(7,sid,tid,turn.text());
            verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),anyString(),argThat(r->((Map<?,?>)r).get("shoppingConstraints").equals(Map.of("3","WIRED"))),isNull());
            verify(tools).execute(eq("SHOPPING"),eq("search_products"),argThat(a->a.path("category").asInt()==3),argThat(c->c.shoppingConstraints.snapshot().equals(Map.of("3","WIRED"))));
            verify(tools,never()).execute(eq("PROCUREMENT"),anyString(),any(),any());
        } finally {service.close();}
    }

    @Test void shoppingReceivesFreshCartAndKnownBudgetAfterHistoricalReceipt()throws Exception{
        turn=new AssistantSessionStore.Turn(tid,sid,2,"预算2000元，看看鼠标","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,turn.text())).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        var prior=new AssistantSessionStore.Turn("prior",sid,1,"第一款要2件","DONE","清单已加入M100两件",null,null,"now","now");
        when(store.turns(7,sid)).thenReturn(List.of(prior,turn));
        when(model.call(eq(tid),eq(0),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"agent\":\"SHOPPING\",\"shoppingMode\":\"SEARCH\",\"connectionChange\":{\"connection\":\"KEEP\"}}"));
        when(tools.shoppingSnapshot(any())).thenAnswer(i->{AssistantTools.Context c=i.getArgument(0);c.shoppingCartSnapshot=Map.of("available",true,"version","9","items",List.of());return c.shoppingCartSnapshot;});
        when(model.call(eq(tid),eq(1),anyString(),anyList(),anyList(),eq(false))).thenReturn(mapper.createObjectNode().put("content","本次查询清单为空，可以继续挑选鼠标。"));
        try{service.send(7,sid,tid,turn.text());
            verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),anyString(),argThat(r->((Map<?,?>)r).containsKey("shoppingCartSnapshot")),isNull());
            verify(model).stream(eq(tid),eq(1),eq("SHOPPING"),argThat(messages->messages.stream().anyMatch(m->m.get("content").toString().contains("\"version\":\"9\""))&&messages.stream().anyMatch(m->m.get("content").toString().contains("2000.00元"))),anyList(),any());
            verify(tools,times(1)).shoppingSnapshot(any());
            verify(model).stream(eq(tid),eq(1),eq("SHOPPING"),argThat(messages->
                messages.stream().filter(m->"system".equals(m.get("role"))).count()==1
                &&messages.stream().noneMatch(m->"assistant".equals(m.get("role")))
                &&messages.stream().noneMatch(m->m.get("content").toString().contains("清单已加入M100两件"))),anyList(),any());
        }finally{service.close();}
    }
    @Test void budgetSupplementRestoresPersistedConstraintsIntoSearchAndNextResult()throws Exception{
        turn=new AssistantSessionStore.Turn(tid,sid,2,"预算2000元，办公室用，两只鼠标","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,turn.text())).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        var previous=new AssistantSessionStore.Turn("previous",sid,1,"只要有线鼠标","DONE","好的",mapper.readTree("{\"shoppingConstraints\":{\"3\":\"WIRED\"}}"),null,"now","now");
        when(store.turns(7,sid)).thenReturn(List.of(previous,turn));
        when(model.call(eq(tid),eq(0),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"agent\":\"SHOPPING\",\"shoppingMode\":\"SEARCH\",\"connectionChange\":{\"connection\":\"KEEP\"}}"));
        when(model.call(eq(tid),eq(1),anyString(),anyList(),eq(List.of()),eq(false))).thenReturn(mapper.createObjectNode().put("content","当前候选按有线要求筛选。"));
        when(tools.execute(eq("SHOPPING"),eq("search_products"),any(),any())).thenReturn(Map.of("cards",List.of()));
        try{service.send(7,sid,tid,turn.text());
            verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),anyString(),argThat(r->((Map<?,?>)r).get("shoppingConstraints").equals(Map.of("3","WIRED"))),isNull());
            verify(tools).execute(eq("SHOPPING"),eq("search_products"),any(),argThat(c->"WIRED".equals(c.shoppingConstraints.snapshot().get("3"))));
        }finally{service.close();}
    }
    @Test void explicitBrowsingSearchesOnceThenAnswersFromCompleteProductFacts()throws Exception{
        turn=new AssistantSessionStore.Turn(tid,sid,1,"有哪些有线鼠标？","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,turn.text())).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        when(model.call(eq(tid),eq(0),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"agent\":\"SHOPPING\",\"shoppingMode\":\"CLARIFY\",\"connectionChange\":{\"connection\":\"KEEP\"}}"));
        var detail=Map.<String,Object>of("function",Map.of("name","read_product"));
        var definitions=List.<Map<String,Object>>of(Map.of("function",Map.of("name","search_products")),detail);
        when(tools.definitions("SHOPPING")).thenReturn(definitions);
        when(tools.execute(eq("SHOPPING"),eq("search_products"),any(),any())).thenReturn(Map.of("cards",List.of()));
        when(model.call(eq(tid),eq(1),eq("SHOPPING"),anyList(),eq(List.of()),eq(false))).thenReturn(mapper.createObjectNode().put("content","正在按你的条件浏览商品"));
        try{service.send(7,sid,tid,turn.text());verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),anyString(),any(),isNull());
            verify(model).stream(eq(tid),eq(1),eq("SHOPPING"),anyList(),eq(List.of()),any());
            verify(tools).execute(eq("SHOPPING"),eq("search_products"),argThat(a->a.path("category").asInt()==3),any());
        }finally{service.close();}
    }
    @Test void persistedReplyAndCardSnapshotShareServerOrder()throws Exception{setup();
        var cards=List.<Map<String,Object>>of(Map.of("id","36","name","M720","price",299),Map.of("id","37","name","M100","price",39));
        when(model.call(eq(tid),eq(0),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"agent\":\"SHOPPING\",\"shoppingMode\":\"SEARCH\",\"connectionChange\":{\"connection\":\"KEEP\"}}"));
        when(model.call(eq(tid),eq(1),anyString(),anyList(),anyList(),eq(false))).thenReturn(mapper.readTree("{\"tool_calls\":[{\"id\":\"search\",\"function\":{\"name\":\"search_products\",\"arguments\":\"{}\"}}]}"));
        when(tools.execute(eq("SHOPPING"),eq("search_products"),any(),any())).thenAnswer(i->{AssistantTools.Context c=i.getArgument(3);c.cards=cards;return Map.of("cards",cards);});
        when(model.call(eq(tid),eq(2),anyString(),anyList(),anyList(),eq(false))).thenReturn(mapper.createObjectNode().put("content","选项1：M100\nM100单价39元。"));
        try{service.send(7,sid,tid,turn.text());verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),
            argThat(a->a.contains("选项 1：M720")&&a.contains("选项 2：M100")&&!a.contains("选项1：M100")),
            argThat(r->((Map<?,?>)r).get("products").equals(cards)&&((Map<?,?>)r).get("cardReferenceVersion").equals(1)),isNull());
        }finally{service.close();}
    }
    @Test void streamChecksOwnershipBeforeReturningAnyProgress(){
        when(store.turn(8,sid,tid)).thenThrow(new com.xucai.common.exceptions.BusinessException(404,"消息不存在"));
        try{assertThrows(RuntimeException.class,()->service.events(8,sid,tid));verifyNoInteractions(model,tools);}finally{service.close();}
    }
    @Test void semanticClarificationStageDoesNotExposeCatalogTools()throws Exception{setup();
        when(model.call(eq(tid),eq(0),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"agent\":\"SHOPPING\",\"shoppingMode\":\"CLARIFY\",\"connectionChange\":{\"connection\":\"KEEP\"}}"));
        when(model.call(eq(tid),eq(1),eq("SHOPPING"),anyList(),eq(List.of()),eq(false))).thenReturn(mapper.createObjectNode().put("content","主要用来办公还是游戏，预算大约多少？"));
        try{service.send(7,sid,tid,"找鼠标");verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),contains("预算"),any(),isNull());verify(tools,never()).execute(anyString(),anyString(),any(),any());verify(model).stream(eq(tid),eq(1),eq("SHOPPING"),anyList(),eq(List.of()),any());}finally{service.close();}
    }
    @Test void generalIdentityUsesRealModelPathWithoutProductEvidence()throws Exception{
        turn=new AssistantSessionStore.Turn(tid,sid,1,"你是什么AI","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,"你是什么AI")).thenReturn(new AssistantSessionStore.Accepted(turn,true));
        when(model.call(eq(tid),eq(0),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"agent\":\"GENERAL\",\"action\":\"OTHER\"}"));
        when(model.call(eq(tid),eq(1),eq("GENERAL"),anyList(),anyList(),eq(false))).thenReturn(mapper.createObjectNode().put("content","我是序采中的AI采购助手，由通义千问提供语言能力。"));
        try{service.send(7,sid,tid,"你是什么AI");verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),contains("通义千问"),any(),isNull());verify(tools,never()).execute(anyString(),anyString(),any(),any());verify(model).stream(eq(tid),eq(1),eq("GENERAL"),anyList(),anyList(),any());}finally{service.close();}
    }
    final ObjectMapper mapper=new ObjectMapper();final AssistantSessionStore store=mock(AssistantSessionStore.class);
    final AssistantModel model=mock(AssistantModel.class);final AssistantTools tools=mock(AssistantTools.class);
    final AssistantOrchestrator service=new AssistantOrchestrator(store,model,tools,mapper,mock(JdbcTemplate.class));
    final String sid=AssistantSessionStoreTest.SESSION,tid=AssistantSessionStoreTest.TURN;
    AssistantSessionStore.Turn turn=new AssistantSessionStore.Turn(tid,sid,1,"找鼠标","RUNNING",null,null,null,"now",null);
    void setup() {doCallRealMethod().when(model).stream(anyString(),anyInt(),anyString(),anyList(),anyList(),any());service.recover();when(model.available()).thenReturn(true);when(store.accept(7,sid,tid,"找鼠标")).thenReturn(new AssistantSessionStore.Accepted(turn,true));when(store.turn(7,sid,tid)).thenReturn(turn);when(store.turns(7,sid)).thenReturn(List.of(turn));}
    void setupOrderQuery() {
        turn=new AssistantSessionStore.Turn(tid,sid,1,"查看我的订单","RUNNING",null,null,null,"now",null);
        setup();when(store.accept(7,sid,tid,"查看我的订单")).thenReturn(new AssistantSessionStore.Accepted(turn,true));
    }
    @Test void routerShortcutCannotSwallowWritesNegationsOrCompoundRequests() throws Exception {
        var route=mapper.readTree("{\"action\":\"CART\"}");
        for(String text:List.of("把第一款在我的采购清单里设为2件，不要下单。","清单里再加两件","先查看清单再减1件","不要查看清单","查看清单并查询订单"))
            assertNull(AssistantOrchestrator.directTool(route,"PROCUREMENT",text));
        assertEquals("read_cart",AssistantOrchestrator.directTool(route,"PROCUREMENT","请查看我的采购清单。"));
        assertEquals("list_orders",AssistantOrchestrator.directTool(mapper.readTree("{\"action\":\"ORDERS\"}"),"PROCUREMENT","看看我的订单"));
        assertNull(AssistantOrchestrator.directTool(mapper.readTree("{\"action\":\"CHECKOUT\"}"),"PROCUREMENT","不要结算"));
    }
    @Test void startupRecoveryMustCompleteBeforeAcceptingMessages(){
        when(model.available()).thenReturn(true);
        try{assertFalse(service.available());assertThrows(RuntimeException.class,()->service.send(7,sid,tid,"找鼠标"));verifyNoInteractions(store);service.recover();assertTrue(service.available());}finally{service.close();}
    }
    @Test void knowledgeCannotAnswerCapabilitiesFromHistoryWithoutCurrentEvidence()throws Exception{setup();
        when(model.call(eq(tid),eq(0),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"agent\":\"KNOWLEDGE\"}"));
        when(model.call(eq(tid),eq(1),anyString(),anyList(),anyList(),eq(false))).thenReturn(mapper.createObjectNode().put("content","这款支持视频输出"));
        try{service.send(7,sid,tid,"找鼠标");verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("FAILED"),anyString(),isNull(),eq("EVIDENCE_REQUIRED"));}finally{service.close();}
    }
    @Test void clarificationFinishesWithoutAnotherModelCall()throws Exception{setup();
        when(model.call(eq(tid),eq(0),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.readTree("{\"content\":\"{\\\"agent\\\":\\\"PROCUREMENT\\\",\\\"connectionChange\\\":{\\\"connection\\\":\\\"KEEP\\\"},\\\"action\\\":\\\"OTHER\\\"}\"}"));
        when(model.call(eq(tid),eq(1),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"tool\":\"ask_clarification\",\"arguments\":{\"reason\":\"PRODUCT\"}}"));
        when(tools.execute(eq("PROCUREMENT"),eq("ask_clarification"),any(),any())).thenAnswer(i->{AssistantTools.Context c=i.getArgument(3);c.clarification="哪一款？";return Map.of();});
        try{service.send(7,sid,tid,"找鼠标");verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),eq("哪一款？"),any(),isNull());verify(model,times(2)).call(anyString(),anyInt(),anyString(),anyList(),anyList(),anyBoolean());}finally{service.close();}}
    @Test void combinedResultKeepsMutationAndOrderFacts(){
        var ctx=new AssistantTools.Context(7,List.of());ctx.cartChanged=true;ctx.cartChanges.add("鼠标：2 → 4 件");ctx.ordersShown=true;ctx.orderSummary="最近订单：已关闭";ctx.cartSummary="旧清单2件";
        String answer=AssistantOrchestrator.procurementReply(ctx,"模型编造已支付");
        assertTrue(answer.contains("2 → 4"));assertTrue(answer.contains("已关闭"));assertTrue(answer.contains("尚未下单"));assertFalse(answer.contains("已支付"));assertFalse(answer.contains("旧清单"));
    }
    @Test void structuredProcurementExecutesThenUsesActualReceipt()throws Exception{setup();
        when(model.call(eq(tid),eq(0),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"agent\":\"PROCUREMENT\",\"connectionChange\":{\"connection\":\"KEEP\"},\"action\":\"OTHER\"}"));
        when(model.call(eq(tid),eq(1),eq("PROCUREMENT"),anyList(),eq(List.of()),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"tool\":\"adjust_cart_quantity\",\"arguments\":{\"position\":1,\"delta\":2}}"));
        when(model.call(eq(tid),eq(2),eq("PROCUREMENT"),anyList(),eq(List.of()),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"tool\":\"finish\",\"arguments\":{}}"));
        when(tools.execute(eq("PROCUREMENT"),eq("adjust_cart_quantity"),any(),any())).thenAnswer(i->{AssistantTools.Context c=i.getArgument(3);c.cartChanged=true;c.cartChanges.add("鼠标：2 → 4 件");return Map.of("quantity",4);});
        try{service.send(7,sid,tid,"找鼠标");verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),contains("2 → 4"),any(),isNull());verify(tools).execute(eq("PROCUREMENT"),eq("adjust_cart_quantity"),argThat(a->a.path("delta").asInt()==2),any());}finally{service.close();}
    }
    @Test void structuredProcurementRejectsUnsupportedTool()throws Exception{setup();
        when(model.call(eq(tid),eq(0),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"agent\":\"PROCUREMENT\",\"connectionChange\":{\"connection\":\"KEEP\"}}"));
        when(model.call(eq(tid),eq(1),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"tool\":\"pay\",\"arguments\":{}}"));
        try{service.send(7,sid,tid,"找鼠标");verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("FAILED"),anyString(),isNull(),eq("TOOL_COMMAND"));verify(tools,never()).execute(anyString(),anyString(),any(),any());}finally{service.close();}
    }
    @Test void orderIntentReadsLiveDataWithoutAnotherModelAnswer()throws Exception{setupOrderQuery();
        when(model.call(eq(tid),eq(0),eq("ROUTER"),anyList(),anyList(),eq(true))).thenReturn(mapper.readTree("{\"content\":\"{\\\"agent\\\":\\\"PROCUREMENT\\\",\\\"connectionChange\\\":{\\\"connection\\\":\\\"KEEP\\\"},\\\"action\\\":\\\"ORDERS\\\"}\"}"));
        when(tools.execute(eq("PROCUREMENT"),eq("list_orders"),any(),any())).thenAnswer(i->{AssistantTools.Context c=i.getArgument(3);c.ordersShown=true;c.orderSummary="已关闭 · ¥89.00";return Map.of();});
        try{service.send(7,sid,tid,"查看我的订单");verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),eq("已关闭 · ¥89.00"),argThat(r->((Map<?,?>)r).get("tools").equals(List.of("list_orders"))),isNull());verify(model,times(1)).call(anyString(),anyInt(),anyString(),anyList(),anyList(),anyBoolean());}finally{service.close();}}
    @Test void failedLiveReadCannotBecomeSuccessfulOrderAnswer()throws Exception{setupOrderQuery();
        when(model.call(eq(tid),eq(0),eq("ROUTER"),anyList(),anyList(),eq(true))).thenReturn(mapper.readTree("{\"content\":\"{\\\"agent\\\":\\\"PROCUREMENT\\\",\\\"connectionChange\\\":{\\\"connection\\\":\\\"KEEP\\\"},\\\"action\\\":\\\"ORDERS\\\"}\"}"));
        when(tools.execute(anyString(),anyString(),any(),any())).thenThrow(new IllegalStateException("unavailable"));
        try{service.send(7,sid,tid,"查看我的订单");verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("FAILED"),anyString(),isNull(),eq("ASSISTANT_FAILED"));verify(tools,times(1)).execute(anyString(),anyString(),any(),any());}finally{service.close();}}
    @Test void directActionsNeverAllowModelSelectedWrites()throws Exception{
        assertEquals("read_cart",AssistantOrchestrator.directTool(mapper.readTree("{\"action\":\"CART\"}"),"PROCUREMENT"));
        assertEquals("open_checkout",AssistantOrchestrator.directTool(mapper.readTree("{\"action\":\"CHECKOUT\"}"),"PROCUREMENT"));
        assertNull(AssistantOrchestrator.directTool(mapper.readTree("{\"action\":\"ORDERS\"}"),"SHOPPING"));
        for(String action:List.of("pay","set_cart_quantity","cancel_order"))assertThrows(RuntimeException.class,()->AssistantOrchestrator.directTool(mapper.readTree("{\"action\":\""+action+"\"}"),"PROCUREMENT"));
    }
    @Test void persistedRetryNeverStartsAnotherModelRequest(){setup();when(store.accept(7,sid,tid,"找鼠标")).thenReturn(new AssistantSessionStore.Accepted(turn,false));
        try{service.send(7,sid,tid,"找鼠标");verify(model,never()).call(anyString(),anyInt(),anyString(),anyList(),anyList(),anyBoolean());}finally{service.close();}}
    @Test void procurementCannotReturnHistoricalAnswerWithoutTool()throws Exception{setup();
        when(model.call(eq(tid),eq(0),eq("ROUTER"),anyList(),anyList(),eq(true))).thenReturn(mapper.readTree("{\"content\":\"{\\\"agent\\\":\\\"PROCUREMENT\\\",\\\"connectionChange\\\":{\\\"connection\\\":\\\"KEEP\\\"}}\"}"));
        when(model.call(eq(tid),eq(1),eq("PROCUREMENT"),anyList(),anyList(),eq(true))).thenReturn(mapper.createObjectNode().put("content","{\"tool\":\"finish\",\"arguments\":{}}"));
        try{service.send(7,sid,tid,"找鼠标");verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("FAILED"),anyString(),isNull(),eq("TOOL_REQUIRED"));verify(tools,never()).execute(anyString(),anyString(),any(),any());}finally{service.close();}}
    @Test void routesThenUsesSpecialistToolThenSavesReply()throws Exception{setup();
        when(model.call(eq(tid),eq(0),eq("ROUTER"),anyList(),anyList(),eq(true))).thenReturn(mapper.readTree("{\"content\":\"{\\\"agent\\\":\\\"SHOPPING\\\",\\\"shoppingMode\\\":\\\"SEARCH\\\",\\\"connectionChange\\\":{\\\"connection\\\":\\\"KEEP\\\"}}\"}"));
        when(model.call(eq(tid),eq(1),eq("SHOPPING"),anyList(),anyList(),eq(false))).thenReturn(mapper.readTree("{\"tool_calls\":[{\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"search_products\",\"arguments\":\"{\\\"category\\\":3,\\\"keyword\\\":\\\"\\\"}\"}}]}"));
        when(tools.execute(eq("SHOPPING"),eq("search_products"),any(),any())).thenReturn(Map.of("cards",List.of()));
        when(model.call(eq(tid),eq(2),eq("SHOPPING"),anyList(),anyList(),eq(false))).thenReturn(mapper.readTree("{\"content\":\"暂时没有找到商品\"}"));
        try{service.send(7,sid,tid,"找鼠标");verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("DONE"),eq("暂时没有找到商品"),any(),isNull());verify(tools,times(1)).execute(eq("SHOPPING"),eq("search_products"),any(),any());}finally{service.close();}}
    @Test void repeatedToolRequestsHaveFiniteLoop()throws Exception{setup();
        when(model.call(eq(tid),eq(0),anyString(),anyList(),anyList(),eq(true))).thenReturn(mapper.readTree("{\"content\":\"{\\\"agent\\\":\\\"SHOPPING\\\",\\\"shoppingMode\\\":\\\"SEARCH\\\",\\\"connectionChange\\\":{\\\"connection\\\":\\\"KEEP\\\"}}\"}"));
        when(model.call(eq(tid),intThat(n->n>0),anyString(),anyList(),anyList(),eq(false))).thenReturn(mapper.readTree("{\"tool_calls\":[{\"id\":\"c\",\"function\":{\"name\":\"search_products\",\"arguments\":\"{}\"}}]}"));
        when(tools.execute(anyString(),anyString(),any(),any())).thenReturn(Map.of());
        try{service.send(7,sid,tid,"找鼠标");verify(store,timeout(5000)).finish(eq(7L),eq(sid),eq(tid),eq("FAILED"),anyString(),isNull(),eq("TOOL_LIMIT"));verify(model,times(5)).call(anyString(),anyInt(),anyString(),anyList(),anyList(),anyBoolean());verify(tools,times(3)).execute(anyString(),anyString(),any(),any());}finally{service.close();}}
}
