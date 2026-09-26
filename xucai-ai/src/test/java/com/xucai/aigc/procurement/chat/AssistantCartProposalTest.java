package com.xucai.aigc.procurement.chat;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.xucai.api.client.catalog.CandidateCatalogClient;
import com.xucai.api.client.trade.AssistantTradeClient;
import com.xucai.api.dto.mall.*;
import com.xucai.api.dto.mall.CandidateCatalogDTO.Product;
import com.xucai.aigc.procurement.retrieval.AssistantEvidenceSearch;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantCartProposalTest {
    @Test void advertisedControlCommandsDoNotNeedModelClassification() {
        assertEquals("CONFIRM", AssistantCartProposal.commandReply(context("确认"), mapper).path("action").asText());
        assertEquals("REJECT", AssistantCartProposal.commandReply(context("取消"), mapper).path("action").asText());
        for (String text : List.of("不确认", "确认改成3件", "确认取消订单", "你确定吗"))
            assertNull(AssistantCartProposal.commandReply(context(text), mapper));
    }
    final ObjectMapper mapper = new ObjectMapper();
    final CandidateCatalogClient catalog = mock(CandidateCatalogClient.class);
    final AssistantTradeClient trade = mock(AssistantTradeClient.class);
    final AssistantTools tools = new AssistantTools(catalog, trade, mapper, mock(AssistantEvidenceSearch.class));

    Product product(long id, String name, long price) {
        return new Product(id,name,3,1L,mapper.createObjectNode(),"PUBLISHED",true,new SaleItemDTO(id,price,20,true,1L),null);
    }
    AssistantTools.Context context(String text) {
        var ctx = new AssistantTools.Context(7,List.of(Map.of("id","90","name","unrelated old card")));
        ctx.requestText = text; ctx.cardReferencesTrusted = true; return ctx;
    }
    JsonNode request(String action, String name, int count, String source, String text) {
        return mapper.createObjectNode().put("action",action).put("name",name).put("position",0)
                .put("purchaseQuantity",count).put("quantitySource",source).put("source",text);
    }
    AssistantTools.Context prepare() throws Exception {
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":7,\"items\":[]}"));
        var p=product(36,"Logitech M720",29900);
        when(catalog.list(null,1,100,7L,2L)).thenReturn(new CandidateCatalogDTO.Products(List.of(p),1L));
        when(catalog.product(36L,7L,2L)).thenReturn(p);
        var ctx=context("我想买M720两件");
        assertTrue(tools.handleCartRequest(request("SET","M720",2,"两件",ctx.requestText),ctx));
        return ctx;
    }
    AssistantTools.Context next(AssistantTools.Context before, String text) {
        var ctx=context(text);
        ctx.cartProposal=mapper.convertValue(mapper.valueToTree(before.cartProposal),new TypeReference<Map<String,Object>>(){});
        ctx.purchaseScope=before.purchaseScope;ctx.budgetCents=before.budgetCents;
        return ctx;
    }
    void act(AssistantTools.Context ctx,String action) {
        assertTrue(tools.handleCartRequest(request(action,"",0,"",ctx.requestText),ctx));
    }
    @Test void namedPurchasePersistsExactTargetAndConfirmsOnlyOnce() throws Exception {
        var prepared=prepare();assertEquals("36",prepared.cartProposal.get("productId"));
        verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
        var confirmed=next(prepared,"确认");act(confirmed,"CONFIRM");
        verify(trade).saveCart(eq(Map.of("expectedVersion",7L,"items",List.of(Map.of("productId",36L,"quantity",2)))),eq(7L),eq(2L));
        assertTrue(confirmed.cartChanged);assertTrue(confirmed.cartProposal.isEmpty());
        assertNull(confirmed.confirmedCartAction);
        act(next(confirmed,"再确认"),"CONFIRM");
        verify(trade,times(1)).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void missingQuantityRetainsTargetUntilUserSuppliesIt() throws Exception {
        prepare();var ctx=context("我想买M720");
        tools.handleCartRequest(request("SET","M720",0,"",ctx.requestText),ctx);
        assertEquals(-1,ctx.cartProposal.get("quantity"));
        var missing=next(ctx,"确认");act(missing,"CONFIRM");assertFalse(missing.cartProposal.isEmpty());
        var revised=next(missing,"两件");tools.handleCartRequest(request("SET","M720",2,"两件",revised.requestText),revised);
        assertEquals("36",revised.cartProposal.get("productId"));assertEquals(2,revised.cartProposal.get("quantity"));
        verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void explicitFinalQuantityOverridesPendingIncrement() throws Exception {
        prepare();when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":7,\"items\":[{\"productId\":36,\"quantity\":3}]}"));
        var ctx=context("M720再加两件");tools.handleCartRequest(request("ADD","M720",2,"两件",ctx.requestText),ctx);
        assertEquals(5,ctx.cartProposal.get("quantity"));
        var revised=next(ctx,"改成一件");tools.handleCartRequest(request("REVISE","",1,"一件",revised.requestText),revised);
        assertEquals(1,revised.cartProposal.get("quantity"));verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void quotedIncrementCannotBecomeFinalQuantityThroughRouterLabel() throws Exception {
        prepare();when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":7,\"items\":[{\"productId\":36,\"quantity\":4}]}"));
        for (String action : List.of("ADD","SET","REVISE")) {
            var ctx=context("M720再加1件");tools.handleCartRequest(request(action,"M720",1,"1件",ctx.requestText),ctx);
            assertEquals(5,ctx.cartProposal.get("quantity"));assertEquals("ADD",ctx.cartProposal.get("quantityMode"));
        }
        var ctx=context("M720再来1件");tools.handleCartRequest(request("REVISE","M720",1,"1件",ctx.requestText),ctx);
        var confirmed=next(ctx,"确认");act(confirmed,"CONFIRM");
        verify(trade).saveCart(eq(Map.of("expectedVersion",7L,"items",List.of(Map.of("productId",36L,"quantity",5)))),eq(7L),eq(2L));
    }
    @Test void explicitModelCodeResolvesDespiteExpandedModelSuppliedName() throws Exception {
        prepare();var ctx=context("M720买两件");
        tools.handleCartRequest(request("SET","Logitech M720",2,"两件",ctx.requestText),ctx);
        assertEquals("36",ctx.cartProposal.get("productId"));assertEquals(2,ctx.cartProposal.get("quantity"));
        verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void confirmationCannotSwitchProductOrTurnSingleRemovalIntoClear() throws Exception {
        var different=next(prepare(),"确认买M100");act(different,"CONFIRM");
        assertTrue(different.cartProposal.isEmpty());assertTrue(different.clarification.contains("不一致"));
        var ordinal=next(prepare(),"确认第二款");act(ordinal,"CONFIRM");assertFalse(ordinal.cartChanged);
        var clear=context("确认删除K270");clear.cartProposal=Map.of("operation","CLEAR","version",7,"createdAt",System.currentTimeMillis());
        act(clear,"CONFIRM");assertTrue(clear.cartProposal.isEmpty());verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void aNewExplicitModelReplacesPendingTargetEvenWithAnExpandedName() throws Exception {
        var pending=prepare();var other=product(2,"Logitech M100",3900);
        when(catalog.list(null,1,100,7L,2L)).thenReturn(new CandidateCatalogDTO.Products(List.of(other),1L));
        var revised=next(pending,"M100买两件");
        tools.handleCartRequest(request("SET","Logitech M100",2,"两件",revised.requestText),revised);
        assertEquals("2",revised.cartProposal.get("productId"));
        verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void bareQuantityUsesSavedTargetWithoutDependingOnModelLabels() throws Exception {
        var ctx=next(prepare(),"两件");
        var request=AssistantCartProposal.quantityReply(ctx,mapper);assertNotNull(request);
        tools.handleCartRequest(request,ctx);assertEquals(2,ctx.cartProposal.get("quantity"));
        verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
        for(String question:List.of("两件多少钱？","不要两件","确认两件","如果是两件")) {
            ctx.requestText=question;assertNull(AssistantCartProposal.quantityReply(ctx,mapper));
        }
        assertNull(AssistantCartProposal.quantityReply(context("两件"),mapper));
    }
    @Test void categoryIntentDoesNotSelectTheOnlyMatchingProductForPurchase() throws Exception {
        prepare();var only=product(2,"罗技 M100 有线鼠标",3900);
        when(catalog.list(null,1,100,7L,2L)).thenReturn(new CandidateCatalogDTO.Products(List.of(only),1L));
        var ctx=context("预算200元买四个有线鼠标");
        assertFalse(tools.handleCartRequest(request("SET","有线鼠标",4,"四个",ctx.requestText),ctx));
        assertTrue(ctx.needsProductSelection);assertTrue(ctx.cartProposal.isEmpty());
        verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void rejectingProposalNeverChangesCart() throws Exception {
        var canceled=next(prepare(),"不要了");act(canceled,"REJECT");
        assertTrue(canceled.cartProposal.isEmpty());assertFalse(canceled.cartChanged);
        act(next(canceled,"确认"),"CONFIRM");verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void reviseCommittedCartItemBuildsANewProposal() throws Exception {
        prepare();when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":8,\"items\":[{\"productId\":36,\"quantity\":2}]}"));
        for (String text : List.of("M720改成一件", "算了，改成一件")) {
            var ctx=context(text);ctx.lastCartProductId=36;
            tools.handleCartRequest(request("REVISE","M720",1,"一件",text),ctx);
            assertEquals("36",ctx.cartProposal.get("productId"));assertEquals(1,ctx.cartProposal.get("quantity"));
            assertEquals(2,ctx.cartProposal.get("previous"));
        }
        var unknown=context("改成一件");unknown.lastCartProductId=90;
        tools.handleCartRequest(request("REVISE","",1,"一件",unknown.requestText),unknown);
        assertTrue(unknown.cartProposal.isEmpty());
        verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void cancelWithoutPendingIsANormalNoOp() {
        var ctx=context("不要了");act(ctx,"REJECT");
        assertTrue(ctx.clarification.contains("没有待取消"));assertFalse(ctx.cartChanged);
        verifyNoInteractions(trade);
    }
    @Test void confirmationWithDifferentQuantityPreparesRevisionInsteadOfExecutingOldQuantity() throws Exception {
        var changed=next(prepare(),"确认，但改成一件");
        tools.handleCartRequest(request("CONFIRM","",1,"一件",changed.requestText),changed);
        assertEquals(1,changed.cartProposal.get("quantity"));verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
        var confirmed=next(changed,"好的");act(confirmed,"CONFIRM");
        verify(trade).saveCart(argThat(m->mapper.valueToTree(m).path("items").get(0).path("quantity").asInt()==1),eq(7L),eq(2L));
    }
    @Test void concurrentCartChangeInvalidatesPendingOperation() throws Exception {
        var confirmed=next(prepare(),"确认");
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":8,\"items\":[]}"));
        act(confirmed,"CONFIRM");assertTrue(confirmed.clarification.contains("发生变化"));
        assertTrue(confirmed.cartProposal.isEmpty());verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void secondVersionReadAlsoRejectsConcurrentChanges() throws Exception {
        var confirmed=next(prepare(),"确认");
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":7,\"items\":[]}"),mapper.readTree("{\"version\":8,\"items\":[]}"));
        act(confirmed,"CONFIRM");assertFalse(confirmed.cartChanged);verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void changedPriceAndExpiryRequireFreshProposal() throws Exception {
        var original=prepare();var confirmed=next(original,"确认");
        when(catalog.product(36L,7L,2L)).thenReturn(product(36,"Logitech M720",39900));
        act(confirmed,"CONFIRM");assertTrue(confirmed.clarification.contains("价格或状态"));
        var expired=next(original,"确认");expired.cartProposal.put("createdAt",System.currentTimeMillis()-16*60_000L);
        act(expired,"CONFIRM");assertTrue(expired.clarification.contains("过期"));
        verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void namedRemovalResolvesOwnCartNotOldCards() throws Exception {
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":7,\"items\":[{\"productId\":37,\"quantity\":2},{\"productId\":38,\"quantity\":2}]}"));
        when(catalog.product(37L,7L,2L)).thenReturn(product(37,"Logitech K270",14900));
        when(catalog.product(38L,7L,2L)).thenReturn(product(38,"Logitech K780",49900));
        var ctx=context("把清单里的K270删掉");tools.handleCartRequest(request("REMOVE","K270",0,"",ctx.requestText),ctx);
        assertEquals("37",ctx.cartProposal.get("productId"));assertEquals(0,ctx.cartProposal.get("quantity"));
        verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
        var confirmed=next(ctx,"确认");act(confirmed,"CONFIRM");
        verify(trade).saveCart(eq(Map.of("expectedVersion",7L,"items",List.of(Map.of("productId",38L,"quantity",2)))),eq(7L),eq(2L));
    }
    @Test void clearRequiresConfirmationAndNeverTouchesOrders() throws Exception {
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":7,\"items\":[{\"productId\":37,\"quantity\":2}]}"));
        var ctx=context("清空我的采购清单");act(ctx,"CLEAR");
        verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
        act(next(ctx,"确认"),"CONFIRM");
        verify(trade).saveCart(eq(Map.of("expectedVersion",7L,"items",List.of())),eq(7L),eq(2L));
        verify(trade,never()).orders(anyLong(),anyLong());verifyNoInteractions(catalog);
    }
    @Test void confirmedOperationStillUsesBudgetGuard() throws Exception {
        var ctx=next(prepare(),"确认");ctx.purchaseScope=new AssistantPurchaseScope(20000,2,false,Map.of());ctx.budgetCents=20000L;
        act(ctx,"CONFIRM");assertFalse(ctx.cartChanged);assertTrue(ctx.clarification.contains("598.00"));
        verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void fabricatedSourceAndUnresolvedProductDoNotWrite() throws Exception {
        var ctx=prepare();var request=request("SET","imaginary",2,"两件","旧消息");
        assertFalse(tools.handleCartRequest(request,ctx));
        ctx.requestText="买不存在的型号两件";
        tools.handleCartRequest(request("SET","不存在",2,"两件",ctx.requestText),ctx);
        assertTrue(ctx.cartProposal.isEmpty());verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
}
