package com.xucai.aigc.procurement.chat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.client.catalog.CandidateCatalogClient;
import com.xucai.api.client.trade.AssistantTradeClient;
import com.xucai.common.exceptions.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantToolsTest {
    @Test void batchBudgetAllowsMiceWithoutChargingExistingKeyboardsAndStillRejectsOverrun() throws Exception {
        var baseline=mapper.readTree("{\"version\":44,\"items\":[{\"productId\":38,\"quantity\":2}]}");
        var ctx=context("第二款要4件");
        ctx.purchaseScope=AssistantPurchaseScope.capture(20000,4,false,baseline);ctx.budgetCents=20000L;
        when(trade.cart(7,2)).thenReturn(baseline);
        when(catalog.product(35L,7L,2L)).thenReturn(new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(35L,"M100",3,1L,mapper.createObjectNode(),"PUBLISHED",true,new com.xucai.api.dto.mall.SaleItemDTO(35L,3900L,10,true,1L),null));
        tools.execute("PROCUREMENT","set_cart_quantity",mapper.readTree("{\"position\":2,\"quantity\":4}"),ctx);
        assertTrue(ctx.cartChanged);
        verify(trade).saveCart(eq(Map.of("expectedVersion",44L,"items",List.of(Map.of("productId",38L,"quantity",2),Map.of("productId",35L,"quantity",4)))),eq(7L),eq(2L));
        verify(catalog,never()).product(38L,7L,2L);
        var next=context("第二款要6件");next.purchaseScope=ctx.purchaseScope;next.budgetCents=20000L;
        tools.execute("PROCUREMENT","set_cart_quantity",mapper.readTree("{\"position\":2,\"quantity\":6}"),next);
        assertFalse(next.cartChanged);assertTrue(next.clarification.contains("234.00"));
        verify(trade,times(1)).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void cartSummarySeparatesBatchSubtotalFromWholeCart() throws Exception {
        var ctx=context();ctx.budgetCents=20000L;ctx.purchaseScope=new AssistantPurchaseScope(20000,4,false,Map.of("38",2));
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":46,\"items\":[{\"productId\":38,\"quantity\":2},{\"productId\":35,\"quantity\":4}]}"));
        when(catalog.product(38L,7L,2L)).thenReturn(new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(38L,"K780",4,1L,mapper.createObjectNode(),"PUBLISHED",true,new com.xucai.api.dto.mall.SaleItemDTO(38L,49900L,10,true,1L),null));
        when(catalog.product(35L,7L,2L)).thenReturn(new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(35L,"M100",3,1L,mapper.createObjectNode(),"PUBLISHED",true,new com.xucai.api.dto.mall.SaleItemDTO(35L,3900L,10,true,1L),null));
        tools.execute("PROCUREMENT","read_cart",mapper.createObjectNode(),ctx);
        assertTrue(ctx.cartSummary.contains("合计 ¥1154.00"));assertTrue(ctx.cartSummary.contains("本次新增采购合计 ¥156.00"));
        assertTrue(ctx.cartSummary.contains("未超出预算"));verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void shoppingCardsFilterBatchPriceBeforeAssigningPositions() throws Exception {
        var ctx=new AssistantTools.Context(7,List.of());
        ctx.purchaseScope=new AssistantPurchaseScope(20000,4,false,Map.of());
        var costly=new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(36L,"M720",3,1L,mapper.createObjectNode(),"PUBLISHED",true,new com.xucai.api.dto.mall.SaleItemDTO(36L,29900L,10,true,1L),null);
        var fits=new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(35L,"M100",3,1L,mapper.createObjectNode(),"PUBLISHED",true,new com.xucai.api.dto.mall.SaleItemDTO(35L,3900L,10,true,1L),null);
        when(catalog.list(3,1,100,7L,2L)).thenReturn(new com.xucai.api.dto.mall.CandidateCatalogDTO.Products(List.of(costly,fits),2L));
        tools.execute("SHOPPING","search_products",mapper.readTree("{\"category\":3,\"keyword\":\"\"}"),ctx);
        assertEquals(List.of("35"),ctx.cards.stream().map(c->c.get("id")).toList());
        assertTrue(ctx.cardReferencesTrusted);verifyNoInteractions(trade);
    }
    @Test void knowledgeProductReadIncludesCurrentEvidenceWithoutASecondModelStep() throws Exception {
        var ctx=context();ctx.turnId="detail";
        var product=new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(35L,"M185",3,1L,mapper.createObjectNode(),"PUBLISHED",true,null,null);
        when(catalog.product(35L,7L,2L)).thenReturn(product);
        when(evidenceSearch.search(anyString(),eq(35L),eq(7L),anyString())).thenReturn(
                new com.xucai.aigc.procurement.retrieval.AssistantEvidenceSearch.Result(true,false,"KEYWORD","NOT_BUILT",List.of(),0,true,"",Map.of()));
        var result=mapper.valueToTree(tools.execute("KNOWLEDGE","read_product",mapper.readTree("{\"position\":2}"),ctx));
        assertEquals("M185",result.path("product").path("name").asText());
        assertEquals("KEYWORD",result.path("evidence").path("mode").asText());
        assertEquals(1,ctx.retrievals.size());verifyNoInteractions(trade);
    }
    @Test void comparisonResolvesTogetherAndKeepsEvidencePerProductWithoutWrites() throws Exception {
        var ctx=new AssistantTools.Context(7,List.of());ctx.turnId="comparison";
        ctx.requestText="M330和M185有什么区别";
        var a=new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(11L,"M330",3,1L,mapper.createObjectNode(),"PUBLISHED",true,null,null);
        var b=new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(12L,"M185",3,1L,mapper.createObjectNode(),"PUBLISHED",true,null,null);
        when(catalog.list(null,1,100,7L,2L)).thenReturn(new com.xucai.api.dto.mall.CandidateCatalogDTO.Products(List.of(b,a),2L));
        var evidence=new com.xucai.aigc.procurement.retrieval.AssistantEvidenceSearch.Result(true,false,"KEYWORD","NOT_BUILT",List.of(),0,true,"",Map.of());
        when(evidenceSearch.search(anyString(),anyLong(),eq(7L),anyString())).thenReturn(evidence);
        var result=mapper.valueToTree(tools.execute("SHOPPING","compare_products",mapper.readTree("{\"keywords\":[\"M330\",\"M185\"]}"),ctx));
        assertTrue(result.path("complete").asBoolean());
        assertTrue(ctx.cardReferencesTrusted);
        assertEquals(List.of("11","12"),ctx.cards.stream().map(c->c.get("id")).toList());
        assertEquals(List.of(11L,12L),ctx.retrievals.stream().map(r->r.get("productId")).toList());
        verify(catalog,times(1)).list(null,1,100,7L,2L);
        verify(evidenceSearch).search(eq("comparison:101"),eq(11L),eq(7L),anyString());
        verify(evidenceSearch).search(eq("comparison:102"),eq(12L),eq(7L),anyString());
        verifyNoInteractions(trade);assertFalse(ctx.cartChanged);
    }
    @Test void ambiguousComparisonKeepsOldCardsAndDoesNotRetrieveOrWrite() throws Exception {
        var ctx=context();ctx.requestText="比较M330和M185";
        var a=new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(11L,"M330黑色",3,1L,mapper.createObjectNode(),"PUBLISHED",true,null,null);
        var b=new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(12L,"M330白色",3,1L,mapper.createObjectNode(),"PUBLISHED",true,null,null);
        when(catalog.list(null,1,100,7L,2L)).thenReturn(new com.xucai.api.dto.mall.CandidateCatalogDTO.Products(List.of(a,b),2L));
        var original=List.copyOf(ctx.cards);
        var result=mapper.valueToTree(tools.execute("KNOWLEDGE","compare_products",mapper.readTree("{\"keywords\":[\"M330\",\"M185\"]}"),ctx));
        assertFalse(result.path("complete").asBoolean());assertEquals(original,ctx.cards);
        assertTrue(ctx.clarification.contains("M330".toLowerCase()));verifyNoInteractions(trade,evidenceSearch);
    }
    @Test void comparisonRejectsInventedAndUnboundedObjectsBeforeReading() throws Exception {
        var ctx=context();ctx.requestText="比较M330和M185";
        for(String json:List.of("{\"keywords\":[\"M330\",\"M720\"]}","{\"keywords\":[\"M330\",\"M185\",\"M100\",\"K780\"]}","{\"keywords\":[\"M330\",\"M330\"]}"))
            assertThrows(BusinessException.class,()->tools.execute("SHOPPING","compare_products",mapper.readTree(json),ctx));
        verifyNoInteractions(catalog,trade,evidenceSearch);
    }
    @Test void cartDescriptionLoadsOwnProductsWithoutAddressOrWrites() throws Exception {
        var ctx=context();ctx.describingCart=true;
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":10,\"address\":\"private-address\",\"items\":[{\"productId\":35,\"quantity\":2},{\"productId\":36,\"quantity\":1}]}"));
        var product=new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(35L,"键盘",4,1L,mapper.readTree("{\"connection\":\"Bluetooth\"}"),"PUBLISHED",true,null,null);
        when(catalog.product(35L,7L,2L)).thenReturn(product);
        when(catalog.product(36L,7L,2L)).thenThrow(new IllegalStateException("unavailable"));
        var data=mapper.valueToTree(tools.shoppingSnapshot(ctx));
        assertEquals("Bluetooth",data.path("items").get(0).path("product").path("specJson").path("connection").asText());
        assertEquals(2,data.path("items").get(0).path("quantity").asInt());
        assertFalse(data.path("items").get(1).path("available").asBoolean());
        assertFalse(data.toString().contains("private-address"));
        verify(trade).cart(7,2);verifyNoMoreInteractions(trade);
        assertFalse(ctx.cartChanged);assertFalse(ctx.checkout);
    }

    @Test void shoppingCartReceiptUsesRealQuantityOrExplicitUnknown(){
        assertEquals("你的采购清单目前为空。",AssistantTools.shoppingCartSummary(Map.of("available",true,"items",List.of())));
        assertTrue(AssistantTools.shoppingCartSummary(Map.of("available",true,"items",List.of(Map.of("name","M100","quantity",3)))).contains("M100 × 3 件"));
        assertFalse(AssistantTools.shoppingCartSummary(Map.of("available",false)).contains("为空"));
    }
    @Test void shoppingReadsFreshEmptyCartWithoutOpeningCheckout()throws Exception{
        var ctx=context();
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":8,\"items\":[],\"address\":\"secret\"}"));
        var snapshot=tools.shoppingSnapshot(ctx);
        assertEquals(true,snapshot.get("available"));assertEquals(List.of(),snapshot.get("items"));
        assertFalse(mapper.writeValueAsString(snapshot).contains("secret"));
        assertFalse(ctx.checkout);assertFalse(ctx.cartChanged);verifyNoInteractions(catalog);
    }
    @Test void shoppingSnapshotDoesNotConfuseUnavailableWithEmpty(){
        when(trade.cart(7,2)).thenThrow(new IllegalStateException("offline"));
        var snapshot=tools.shoppingSnapshot(context());
        assertEquals(false,snapshot.get("available"));assertFalse(snapshot.containsKey("items"));
        verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void shoppingSnapshotUsesAuthoritativeQuantityAndSafeFields()throws Exception{
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":9,\"items\":[{\"productId\":35,\"quantity\":3,\"phone\":\"secret\"}]}"));
        when(catalog.product(35L,7L,2L)).thenThrow(new IllegalStateException("unpublished"));
        var snapshot=tools.shoppingSnapshot(context());
        assertEquals(List.of(Map.of("productId","35","name","商品名称暂不可用","quantity",3)),snapshot.get("items"));
        assertFalse(mapper.writeValueAsString(snapshot).contains("secret"));verify(trade).cart(7,2);
    }
    @Test void searchFiltersBeforeLimitButKnowledgeCanInspectExcludedModel()throws Exception{
        var wired=new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(2L,"M100",3,1L,mapper.readTree("{\"connection\":\"USB 有线\"}"),"PUBLISHED",true,null,null);
        var wireless=new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(36L,"M720",3,1L,mapper.readTree("{\"connections\":[\"Bluetooth\",\"Unifying\"]}"),"PUBLISHED",true,null,null);
        when(catalog.list(3,1,100,7L,2L)).thenReturn(new com.xucai.api.dto.mall.CandidateCatalogDTO.Products(List.of(wireless,wired),2L));
        var ctx=context();ctx.shoppingConstraints.restore(mapper.readTree("{\"3\":\"WIRED\"}"));
        var args=mapper.readTree("{\"category\":3,\"keyword\":\"\"}");
        tools.execute("SHOPPING","search_products",args,ctx);
        assertEquals(List.of("2"),ctx.cards.stream().map(c->c.get("id")).toList());
        ctx.cardReferencesTrusted=false;
        tools.execute("KNOWLEDGE","search_products",args,ctx);
        assertEquals(2,ctx.cards.size());assertTrue(ctx.cardReferencesTrusted);verifyNoInteractions(trade);
    }
    @Test void catalogSearchRecordsActualFiltersAndNeverBroadensUnknownModel()throws Exception{
        var wired=new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(2L,"M100",3,1L,mapper.readTree("{\"connection\":\"USB 有线\"}"),"PUBLISHED",true,null,null);
        when(catalog.list(3,1,100,7L,2L)).thenReturn(new com.xucai.api.dto.mall.CandidateCatalogDTO.Products(List.of(wired),1L));
        var ctx=context();
        tools.execute("SHOPPING","search_products",mapper.readTree("{\"category\":3,\"keyword\":\"不存在型号\"}"),ctx);
        assertTrue(ctx.cards.isEmpty());
        assertEquals(Map.of("category",3,"keyword","不存在型号","returnedProductIds",List.of()),ctx.catalogSearches.get(0));
        tools.execute("SHOPPING","search_products",mapper.readTree("{\"category\":3,\"keyword\":\"\"}"),ctx);
        assertEquals(List.of("2"),ctx.catalogSearches.get(1).get("returnedProductIds"));
        verifyNoInteractions(trade);
    }
    @Test void staleOrKnowledgeCardCannotBypassConnectionRequirement()throws Exception{
        var ctx=context("第二款要4件");ctx.shoppingConstraints.restore(mapper.readTree("{\"3\":\"WIRED\"}"));
        when(catalog.product(35L,7L,2L)).thenReturn(new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(35L,"无线鼠标",3,1L,mapper.readTree("{\"connection\":\"USB无线接收器\"}"),"PUBLISHED",true,null,null));
        tools.execute("PROCUREMENT","set_cart_quantity",mapper.readTree("{\"position\":2,\"quantity\":4}"),ctx);
        assertFalse(ctx.cartChanged);assertTrue(ctx.clarification.contains("连接方式"));verifyNoInteractions(trade);
    }
    @Test void legacyCardNumbersNeverAuthorizeAWritingTool()throws Exception{
        var ctx=context("第二款要4件");ctx.cardReferencesTrusted=false;
        tools.execute("PROCUREMENT","set_cart_quantity",mapper.readTree("{\"position\":2,\"quantity\":4}"),ctx);
        assertFalse(ctx.cartChanged);assertTrue(ctx.clarification.contains("重新核对"));
        verifyNoInteractions(trade,catalog);
    }
    @Test void genericPurchaseCannotBecomeModelSelectedCartWrite()throws Exception{
        var ctx=context("预算200元，买四个办公室用的有线鼠标");
        tools.execute("PROCUREMENT","set_cart_quantity",mapper.readTree("{\"position\":2,\"quantity\":4}"),ctx);
        assertFalse(ctx.cartChanged);assertTrue(ctx.clarification.contains("还没有修改"));verifyNoInteractions(trade,catalog);
    }
    @Test void explicitSelectionOverBudgetDoesNotWrite()throws Exception{
        var ctx=context("第二款要4件");ctx.requirementSources=List.of("预算200元",ctx.requestText);
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":5,\"items\":[]}"));
        when(catalog.product(35L,7L,2L)).thenReturn(new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(35L,"无线鼠标",3,1L,mapper.createObjectNode(),"PUBLISHED",true,new com.xucai.api.dto.mall.SaleItemDTO(35L,8900L,10,true,1L),null));
        tools.execute("PROCUREMENT","set_cart_quantity",mapper.readTree("{\"position\":2,\"quantity\":4}"),ctx);
        assertFalse(ctx.cartChanged);assertTrue(ctx.clarification.contains("356.00"));verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void cartSummaryAddsRealPricesAndComparesBudget()throws Exception{
        var ctx=context();ctx.requirementSources=List.of("预算200元");
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"items\":[{\"productId\":35,\"quantity\":4}]}"));
        when(catalog.product(35L,7L,2L)).thenReturn(new com.xucai.api.dto.mall.CandidateCatalogDTO.Product(35L,"鼠标",3,1L,mapper.createObjectNode(),"PUBLISHED",true,new com.xucai.api.dto.mall.SaleItemDTO(35L,3900L,10,true,1L),null));
        tools.execute("PROCUREMENT","read_cart",mapper.createObjectNode(),ctx);
        assertTrue(ctx.cartSummary.contains("156.00"));assertTrue(ctx.cartSummary.contains("未超出预算"));verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void knowledgeClarificationDoesNotAskToChangeCart()throws Exception{
        var ctx=new AssistantTools.Context(7,List.of());
        tools.execute("KNOWLEDGE","ask_clarification",mapper.readTree("{\"reason\":\"PRODUCT\"}"),ctx);
        assertTrue(ctx.clarification.contains("了解"));
        assertFalse(ctx.clarification.contains("调整"));
        verifyNoInteractions(trade,catalog,evidenceSearch);
    }
    final ObjectMapper mapper=new ObjectMapper();final CandidateCatalogClient catalog=mock(CandidateCatalogClient.class);
    final AssistantTradeClient trade=mock(AssistantTradeClient.class);
    final com.xucai.aigc.procurement.retrieval.AssistantEvidenceSearch evidenceSearch=mock(com.xucai.aigc.procurement.retrieval.AssistantEvidenceSearch.class);
    final AssistantTools tools=new AssistantTools(catalog,trade,mapper,evidenceSearch);
    AssistantTools.Context context(){return context("");}
    AssistantTools.Context context(String request){var ctx=new AssistantTools.Context(7,List.of(Map.of("id","38","name","设备甲"),Map.of("id","35","name","设备乙")));ctx.requestText=request;ctx.cardReferencesTrusted=true;return ctx;}
    @Test void shoppingCannotWriteCartAndNoAgentCanPay(){
        for(String agent:AssistantTools.ALLOWED.keySet())assertThrows(BusinessException.class,()->tools.execute(agent,"pay",mapper.createObjectNode(),context()));
        assertThrows(BusinessException.class,()->tools.execute("SHOPPING","set_cart_quantity",mapper.createObjectNode(),context()));verifyNoInteractions(trade,catalog);
    }
    @Test void ambiguousOrInventedPositionDoesNotTouchBusiness(){
        for(String args:List.of("{\"position\":0,\"quantity\":2}","{\"position\":3,\"quantity\":2}","{\"position\":1.5,\"quantity\":2}","{\"productId\":35,\"quantity\":2}"))
            assertThrows(BusinessException.class,()->tools.execute("PROCUREMENT","set_cart_quantity",mapper.readTree(args),context()));verifyNoInteractions(trade,catalog);
    }
    @Test void secondCardMapsToServerProductAndUsesCurrentCartVersion()throws Exception{
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":5,\"items\":[{\"productId\":38,\"quantity\":1}]}"));
        var ctx=context("第二款要4件");tools.execute("PROCUREMENT","set_cart_quantity",mapper.readTree("{\"position\":2,\"quantity\":4}"),ctx);
        verify(trade).saveCart(eq(Map.of("expectedVersion",5L,"items",List.of(Map.of("productId",38L,"quantity",1),Map.of("productId",35L,"quantity",4)))),eq(7L),eq(2L));assertTrue(ctx.cartChanged);
    }
    @Test void writeFailureIsNeverRetried()throws Exception{
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":5,\"items\":[]}"));
        when(trade.saveCart(anyMap(),eq(7L),eq(2L))).thenThrow(new IllegalStateException("timeout"));var ctx=context("第一款要1件");
        assertThrows(IllegalStateException.class,()->tools.execute("PROCUREMENT","set_cart_quantity",mapper.readTree("{\"position\":1,\"quantity\":1}"),ctx));
        verify(trade,times(1)).saveCart(anyMap(),eq(7L),eq(2L));assertFalse(ctx.cartChanged);
    }
    @Test void orderToolStripsAddressAndPhoneBeforeModel()throws Exception{
        when(trade.orders(7,2)).thenReturn(mapper.readTree("{\"items\":[{\"id\":\"o1\",\"status\":\"NO_PAY\",\"address\":{\"phone\":\"secret\"},\"totalAmount\":100}]}"));
        String wire=mapper.writeValueAsString(tools.execute("PROCUREMENT","list_orders",mapper.createObjectNode(),context()));assertFalse(wire.contains("secret"));assertFalse(wire.contains("address"));assertFalse(wire.contains("o1"));assertTrue(wire.contains("待支付"));
    }
    @Test void confirmationOnlyLinksToHumanPage(){var ctx=context();
        tools.execute("PROCUREMENT","open_checkout",mapper.createObjectNode(),ctx);assertTrue(ctx.checkout);verifyNoInteractions(trade,catalog);
    }
    @Test void emptyCartHasCurrentSummaryAndLink()throws Exception{
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"items\":[]}"));var ctx=context();
        tools.execute("PROCUREMENT","read_cart",mapper.createObjectNode(),ctx);
        assertTrue(ctx.cartSummary.contains("空的"));assertTrue(ctx.checkout);verify(trade).cart(7,2);
    }
    @Test void preparingRequirementDoesNotWriteAnyBusinessData()throws Exception{
        var ctx=context();var args=new AssistantRequirementDraftTest().input();
        tools.execute("PROCUREMENT","prepare_requirement",args,ctx);assertNotNull(ctx.requirementDraft);verifyNoInteractions(trade,catalog);
    }
    @Test void routerOnlyAcceptsThreeKnownRoles()throws Exception{
        for(String agent:AssistantTools.ALLOWED.keySet())assertEquals(agent,AssistantOrchestrator.role(mapper.readTree("{\"agent\":\""+agent+"\"}")));
        assertThrows(RuntimeException.class,()->AssistantOrchestrator.role(mapper.readTree("{\"agent\":\"ADMIN\"}")));
    }
    @Test void relativeQuantityUsesServerCartAndRepeatedCallOnlyWritesOnce()throws Exception{
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":9,\"items\":[{\"productId\":35,\"quantity\":3}]}"));
        var ctx=context("第二款再加2件");var args=mapper.readTree("{\"position\":2,\"delta\":2}");
        var first=tools.execute("PROCUREMENT","adjust_cart_quantity",args,ctx);
        assertEquals(first,tools.execute("PROCUREMENT","adjust_cart_quantity",args,ctx));
        verify(trade).saveCart(eq(Map.of("expectedVersion",9L,"items",List.of(Map.of("productId",35L,"quantity",5)))),eq(7L),eq(2L));
        verify(trade,times(1)).cart(7,2);assertEquals(List.of("设备乙：3 → 5 件"),ctx.cartChanges);
        tools.execute("PROCUREMENT","set_cart_quantity",mapper.readTree("{\"position\":2,\"quantity\":7}"),ctx);assertFalse(ctx.clarification.isBlank());
        verify(trade,times(1)).saveCart(anyMap(),eq(7L),eq(2L));
    }
    @Test void relativeZeroRemovesItemButDoesNotTouchOtherItems()throws Exception{
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":4,\"items\":[{\"productId\":35,\"quantity\":2},{\"productId\":38,\"quantity\":1}]}"));
        tools.execute("PROCUREMENT","adjust_cart_quantity",mapper.readTree("{\"position\":2,\"delta\":-2}"),context("第二款减2件"));
        verify(trade).saveCart(eq(Map.of("expectedVersion",4L,"items",List.of(Map.of("productId",38L,"quantity",1)))),eq(7L),eq(2L));
    }
    @Test void relativeOutOfRangeDoesNotWrite()throws Exception{
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":4,\"items\":[{\"productId\":35,\"quantity\":998}]}"));
        for(int delta:List.of(2,-999,0))assertThrows(BusinessException.class,()->tools.execute("PROCUREMENT","adjust_cart_quantity",mapper.readTree("{\"position\":2,\"delta\":"+delta+"}"),context("第二款"+(delta<0?"减":"再加")+Math.abs(delta)+"件")));
        verify(trade,never()).saveCart(anyMap(),anyLong(),anyLong());
    }
    @Test void uncertainMutationCannotBeRetriedWithinTurn()throws Exception{
        when(trade.cart(7,2)).thenReturn(mapper.readTree("{\"version\":4,\"items\":[]}"));
        when(trade.saveCart(anyMap(),eq(7L),eq(2L))).thenThrow(new IllegalStateException("timeout"));
        var ctx=context("第二款再加2件");var args=mapper.readTree("{\"position\":2,\"delta\":2}");
        assertThrows(IllegalStateException.class,()->tools.execute("PROCUREMENT","adjust_cart_quantity",args,ctx));
        assertThrows(BusinessException.class,()->tools.execute("PROCUREMENT","adjust_cart_quantity",args,ctx));
        verify(trade,times(1)).saveCart(anyMap(),eq(7L),eq(2L));assertFalse(ctx.cartChanged);
    }
    @Test void clarificationOnlyUsesKnownQuestionsWithoutBusinessCalls()throws Exception{
        var ctx=context();tools.execute("PROCUREMENT","ask_clarification",mapper.readTree("{\"reason\":\"PRODUCT\"}"),ctx);
        assertTrue(ctx.clarification.contains("哪一款"));
        assertThrows(BusinessException.class,()->tools.execute("PROCUREMENT","ask_clarification",mapper.readTree("{\"reason\":\"已下单\"}"),ctx));verifyNoInteractions(trade,catalog);
    }
    @Test void missingProductIsClarifiedBeforeQuantity()throws Exception{
        var ctx=new AssistantTools.Context(7,List.of());
        tools.execute("PROCUREMENT","ask_clarification",mapper.readTree("{\"reason\":\"QUANTITY\"}"),ctx);
        assertTrue(ctx.clarification.contains("哪一款"));verifyNoInteractions(trade,catalog);
    }
    @Test void identicalEvidenceQueryIsReusedWithinTurn()throws Exception{
        var ctx=context();ctx.turnId="00000000-0000-4000-8000-000000000001";
        var result=new com.xucai.aigc.procurement.retrieval.AssistantEvidenceSearch.Result(true,false,"KEYWORD","NOT_BUILT",List.of(),0,true,"资料不足",Map.of());
        when(evidenceSearch.search(anyString(),eq(38L),eq(7L),eq("蓝牙"))).thenReturn(result);
        var args=mapper.readTree("{\"position\":1,\"query\":\"蓝牙\"}");
        assertSame(result,tools.execute("KNOWLEDGE","read_evidence",args,ctx));assertSame(result,tools.execute("KNOWLEDGE","read_evidence",args,ctx));
        verify(evidenceSearch,times(1)).search(eq(ctx.turnId+":101"),eq(38L),eq(7L),eq("蓝牙"));assertEquals(1,ctx.retrievals.size());
    }
}
