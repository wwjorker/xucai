package com.xucai.aigc.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.client.catalog.CandidateCatalogClient;
import com.xucai.api.dto.mall.*;
import com.xucai.api.dto.mall.CandidateCatalogDTO.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CandidatePlannerTest {
    final ObjectMapper mapper=DashScopeRequirementAnalyzer.strictMapper(new ObjectMapper());
    final CandidateCatalogClient client=mock(CandidateCatalogClient.class);
    final CandidatePlanner planner=new CandidatePlanner(client);
    RequirementDTO req(RequirementDetails d){return new RequirementDTO(43L,10001L,"候选验收","原文不能改预算",d,2L,"OPEN","now","now");}
    RequirementDetails details(){return new RequirementDetails(5000L,3,List.of(1,3),"办公",List.of("USB"),List.of(),List.of(new RequirementItem(1,2),new RequirementItem(3,1)));}
    Product product(long id,int category,long price){return new Product(id,"商品"+id,category,1L,mapper.createObjectNode(),"PUBLISHED",true,new SaleItemDTO(id,price,20,true,1L),"可购买");}
    Chunk chunk(long product){return new Chunk("doc:1:v1:c1",product,1L,1L,"chunk-v1",0,8,"不支持视频输出。","资料","https://example.com");}
    CandidatePlanner.Context context(){return new CandidatePlanner.Context(req(details()),List.of(new CandidatePlanner.Entry(product(1,1,1000),2,List.of(chunk(1))),new CandidatePlanner.Entry(product(2,3,500),1,List.of())),false);}
    @Test void unknownAllocationAndExplicitEmptyAreRejected(){
        assertThrows(ModelCallException.class,()->CandidatePlanner.quantities(req(new RequirementDetails(100L,4,List.of(1,3),"办公",List.of(),List.of()))));
        assertThrows(ModelCallException.class,()->CandidatePlanner.quantities(req(new RequirementDetails(100L,2,List.of(1),"办公",List.of(),List.of(),List.of()))));
        assertEquals(Map.of(1,2),CandidatePlanner.quantities(req(new RequirementDetails(100L,2,List.of(1),"办公",List.of(),List.of()))));
    }
    @Test void amountsAndQuotesAreServerFactsAndAllVerdictsUnknown(){
        var result=planner.parse("{\"selections\":{\"1\":{\"productId\":1,\"chunkIds\":[\"doc:1:v1:c1\"]},\"3\":{\"productId\":2,\"chunkIds\":[]}}}",context(),mapper);
        assertEquals(2500,result.candidate().totalAmount());assertEquals(2,result.candidate().items().get(0).quantity());
        assertEquals("不支持视频输出。",result.candidate().items().get(0).evidence().get(0).text());
        assertTrue(result.candidate().items().stream().allMatch(i->"UNKNOWN".equals(i.verdict())));assertFalse(result.candidate().semantic());
    }
    @Test void inventedCrossProductDuplicateAndExtraFieldsRejected(){
        for(String wire:List.of(
                "{\"selections\":{\"1\":{\"productId\":99,\"chunkIds\":[]}}}",
                "{\"selections\":{\"3\":{\"productId\":2,\"chunkIds\":[\"doc:1:v1:c1\"]}}}",
                "{\"selections\":{\"1\":{\"productId\":1,\"chunkIds\":[]},\"1\":{\"productId\":1,\"chunkIds\":[]}}}",
                "{\"selections\":{\"1\":{\"productId\":1,\"chunkIds\":[],\"quantity\":1}}}",
                "{\"selections\":{\"1\":{\"productId\":1.2,\"chunkIds\":[]}}}",
                "{\"selections\":{},\"summary\":\"支持全部条件\"}"))assertThrows(ModelCallException.class,()->planner.parse(wire,context(),mapper));
    }
    @Test void partialCategoriesAndCombinedBudgetOverrunRejected(){
        assertThrows(ModelCallException.class,()->planner.parse("{\"selections\":{\"1\":{\"productId\":1,\"chunkIds\":[]}}}",context(),mapper));
        var ctx=new CandidatePlanner.Context(context().requirement(),List.of(new CandidatePlanner.Entry(product(1,1,2400),2,List.of()),new CandidatePlanner.Entry(product(2,3,500),1,List.of())),false);
        assertThrows(ModelCallException.class,()->planner.parse("{\"selections\":{\"1\":{\"productId\":1,\"chunkIds\":[]},\"3\":{\"productId\":2,\"chunkIds\":[]}}}",ctx,mapper));
    }
    @Test void noSelectionIsExplicitAndOldJsonStillReadable() throws Exception {
        var result=planner.parse("{\"selections\":{}}",context(),mapper);
        assertEquals("NO_SELECTION",result.candidate().status());
        var old=mapper.readValue("{\"conditions\":{\"categoryIds\":[],\"usage\":\"\",\"mustHave\":[],\"optional\":[]},\"questions\":[]}",AnalysisResult.class);
        assertNull(old.candidate());assertEquals(List.of(),old.adjustments());
    }
    @Test void changedPriceAndWrongChunkVersionRejectHistoricalClaims(){
        var result=planner.parse("{\"selections\":{\"1\":{\"productId\":1,\"chunkIds\":[\"doc:1:v1:c1\"]},\"3\":{\"productId\":2,\"chunkIds\":[]}}}",context(),mapper);
        when(client.product(1L,10001L,2L)).thenReturn(product(1,1,1100));
        assertEquals("CANDIDATE_STALE",assertThrows(ModelCallException.class,()->planner.verifyCurrent(result,context().requirement())).code());
        when(client.product(1L,10001L,2L)).thenReturn(product(1,1,1000));
        when(client.locate(1L,"doc:1:v1:c1",10001L,2L)).thenReturn(new DocChunkLocationDTO("doc:1:v1:c1",1L,1L,2L,"chunk-v1",0,8,"不支持视频输出。","不支持视频输出。",true,true,"READY","PUBLISHED"));
        assertEquals("CANDIDATE_STALE",assertThrows(ModelCallException.class,()->planner.verifyCurrent(result,context().requirement())).code());
    }
    @Test void unreadableIndexNeverMasqueradesAsNoEvidence(){
        var input=req(new RequirementDetails(5000L,2,List.of(1),"办公",List.of(),List.of()));
        when(client.list(1,1,20,10001L,2L)).thenReturn(new Products(List.of(product(1,1,1000)),1L));
        when(client.chunks(1L,12,10001L,2L)).thenReturn(new Chunks(List.of(),0L,false,List.of()));
        assertEquals("CANDIDATE_INDEX",assertThrows(ModelCallException.class,()->planner.prepare(input)).code());
        verify(client).list(1,1,20,10001L,2L);
    }
    @Test void normalUnopenedProductSkippedAndTruncationExplicit(){
        var input=req(new RequirementDetails(5000L,2,List.of(1),"办公",List.of(),List.of()));
        var unopened=new Product(9L,"未开售",1,1L,mapper.createObjectNode(),"PUBLISHED",false,null,"暂未开售");
        when(client.list(1,1,20,10001L,2L)).thenReturn(new Products(List.of(unopened,product(1,1,1000)),30L));
        when(client.chunks(1L,12,10001L,2L)).thenReturn(new Chunks(List.of(chunk(1)),1L,true,List.of()));
        var ctx=planner.prepare(input);assertTrue(ctx.partial());assertEquals(1,ctx.products().size());
    }
    @Test void inputBudgetDropsWholeChunksAndMarksLimitedScope(){
        List<Chunk> chunks=new ArrayList<>();for(int i=0;i<12;i++)chunks.add(new Chunk("doc:1:v1:c"+i,1L,1L,1L,"chunk-v1",0,1000,"不".repeat(1000),"长资料","https://example.com"));
        var ctx=new CandidatePlanner.Context(context().requirement(),List.of(new CandidatePlanner.Entry(product(1,1,1000),2,chunks),context().products().get(1)),false);
        java.util.function.ToIntFunction<CandidatePlanner.Context> bytes=c->DashScopeRequirementAnalyzer.requestBody(CandidatePlanner.PROMPT,CandidatePlanner.modelInput(c),mapper).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(bytes.applyAsInt(ctx)>16384);var bounded=CandidatePlanner.bounded(ctx,bytes);
        assertTrue(bytes.applyAsInt(bounded)<=16384);assertTrue(bounded.partial());assertEquals(2,bounded.products().size());
        assertTrue(bounded.products().get(0).chunks().stream().allMatch(c->c.text().length()==1000));
    }
    @Test void smallestOversizeInputFailsWithoutRemovingRequiredCategory(){
        var ctx=new CandidatePlanner.Context(context().requirement(),List.of(new CandidatePlanner.Entry(product(1,1,1000),2,List.of()),new CandidatePlanner.Entry(product(2,3,500),1,List.of())),false);
        assertThrows(ModelCallException.class,()->CandidatePlanner.bounded(ctx,c->20000));
    }
    String selections(long... ids) throws Exception {
        var selections=new LinkedHashMap<String,Object>();
        String[] slots={"1","3"};
        for(int i=0;i<ids.length;i++)selections.put(slots[i],Map.of("productId",ids[i],"chunkIds",List.of()));
        return mapper.writeValueAsString(Map.of("selections",selections));
    }
    void assertCode(String code,String wire,CandidatePlanner.Context ctx){
        assertEquals(code,assertThrows(ModelCallException.class,()->planner.parse(wire,ctx,mapper)).code());
    }
    @Test void differentFailureBranchesHaveDistinctFixedCodes() throws Exception {
        var ctx=new CandidatePlanner.Context(context().requirement(),List.of(context().products().get(0),context().products().get(1),
                new CandidatePlanner.Entry(product(5,1,1200),2,List.of())),false);
        assertCode("CANDIDATE_PRODUCT_UNKNOWN",selections(99,2),ctx);
        assertCode("CANDIDATE_CATEGORY_MISMATCH",selections(1,1),ctx);
        assertCode("CANDIDATE_CATEGORY_MISMATCH",selections(1,5),ctx);
        assertCode("CANDIDATE_CATEGORY_MISSING",selections(1),ctx);
        var expensive=new CandidatePlanner.Context(ctx.requirement(),List.of(new CandidatePlanner.Entry(product(1,1,2400),2,List.of()),ctx.products().get(1)),false);
        assertCode("CANDIDATE_BUDGET_EXCEEDED",selections(1,2),expensive);
        assertEquals(2500,planner.parse(selections(1,2),ctx,mapper).candidate().totalAmount());
    }
    @Test void sourceErrorsAndMalformedJsonDoNotExposeRawOutput(){
        assertCode("CANDIDATE_SOURCE_DUPLICATE","{\"selections\":{\"1\":{\"productId\":1,\"chunkIds\":[\"doc:1:v1:c1\",\"doc:1:v1:c1\"]},\"3\":{\"productId\":2,\"chunkIds\":[]}}}",context());
        assertCode("CANDIDATE_SOURCE_UNKNOWN","{\"selections\":{\"1\":{\"productId\":1,\"chunkIds\":[]},\"3\":{\"productId\":2,\"chunkIds\":[\"doc:1:v1:c1\"]}}}",context());
        for(String raw:List.of("private-synthetic-token", "{\"selections\":{},\"secret\":\"private-synthetic-token\"}",
                "{\"selections\":{},\"selections\":{}}","{\"selections\":{}} {}")){
            var error=assertThrows(ModelCallException.class,()->planner.parse(raw,context(),mapper));
            assertEquals("CANDIDATE_OUTPUT_JSON",error.code());assertFalse(error.getMessage().contains("private-synthetic-token"));
            assertNull(error.getCause());
        }
    }
    @Test void groupedPayloadRoundTripsTwoCategoryCatalogWithoutSendingPrivateIdentity() throws Exception {
        var input=new RequirementDTO(48L,10001L,"PRIVATE_TITLE","PRIVATE_RAW_TEXT",
                new RequirementDetails(100000L,3,List.of(3,4),"办公文档处理",List.of(),List.of("优先有线连接"),
                        List.of(new RequirementItem(3,2),new RequirementItem(4,1))),1L,"OPEN","PRIVATE_DATE","PRIVATE_DATE");
        // 使用项目实际的Long序列化配置，读目录JSON与发给模型的JSON经过同样的mapper。
        var builder=new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder();
        new com.xucai.common.autoconfigure.mvc.JsonConfig().jackson2ObjectMapperBuilderCustomizer().customize(builder);
        ObjectMapper runtimeMapper=DashScopeRequirementAnalyzer.strictMapper(builder.build());
        when(client.list(3,1,20,10001L,2L)).thenReturn(runtimeMapper.readValue(runtimeMapper.writeValueAsString(new Products(List.of(product(30,3,4900)),1L)),Products.class));
        when(client.list(4,1,20,10001L,2L)).thenReturn(runtimeMapper.readValue(runtimeMapper.writeValueAsString(new Products(List.of(product(32,4,9900)),1L)),Products.class));
        var regionalChunk=new Chunk("doc:1:v1:c1",30L,1L,1L,"chunk-v1",0,8,"不支持视频输出。","仅美版资料","https://example.com");
        when(client.chunks(30L,12,10001L,2L)).thenReturn(new Chunks(List.of(regionalChunk),1L,true,List.of()));
        when(client.chunks(32L,12,10001L,2L)).thenReturn(new Chunks(List.of(),0L,true,List.of()));
        var ctx=planner.prepare(input);
        String request=DashScopeRequirementAnalyzer.requestBody(CandidatePlanner.PROMPT,CandidatePlanner.modelInput(ctx),runtimeMapper);
        var envelope=runtimeMapper.readTree(request);var payload=runtimeMapper.readTree(envelope.path("messages").get(1).path("content").asText());
        assertEquals(2,payload.path("categories").size());
        var mice=payload.path("categories").get(0);var keyboards=payload.path("categories").get(1);
        assertEquals(3,mice.path("categoryId").asInt());assertEquals(2,mice.path("quantity").asInt());
        assertEquals("3",mice.path("selectionKey").asText());assertEquals("4",keyboards.path("selectionKey").asText());
        assertEquals(4,keyboards.path("categoryId").asInt());assertEquals(1,keyboards.path("quantity").asInt());
        assertEquals(30,mice.path("options").get(0).path("productId").asLong());
        assertEquals(9800,mice.path("options").get(0).path("lineAmountCents").asLong());
        assertEquals("不支持视频输出。",mice.path("options").get(0).path("chunks").get(0).path("text").asText());
        assertEquals("仅美版资料",mice.path("options").get(0).path("chunks").get(0).path("docTitle").asText());
        for(String forbidden:List.of("userId","requirementId","PRIVATE_","sourceUrl","saleVersion","docId","charStart","purchaseMessage"))assertFalse(request.contains(forbidden),forbidden);
        assertFalse(mice.path("options").get(0).has("id"));
        assertCode("CANDIDATE_PRODUCT_UNKNOWN","{\"selections\":{\"3\":{\"productId\":3,\"chunkIds\":[]},\"4\":{\"productId\":4,\"chunkIds\":[]}}}",ctx); // 分类ID不能当商品ID。
        var result=planner.parse("{\"selections\":{\"3\":{\"productId\":\"30\",\"chunkIds\":[\"doc:1:v1:c1\"]},\"4\":{\"productId\":\"32\",\"chunkIds\":[]}}}",ctx,runtimeMapper);
        assertEquals(19700,result.candidate().totalAmount());assertEquals(2,result.candidate().items().size());
        assertEquals(input.structured(),result.conditions());assertTrue(result.candidate().items().stream().allMatch(i->"UNKNOWN".equals(i.verdict())));
        when(client.product(30L,10001L,2L)).thenReturn(product(30,3,4900));when(client.product(32L,10001L,2L)).thenReturn(product(32,4,9900));
        when(client.locate(30L,"doc:1:v1:c1",10001L,2L)).thenReturn(new DocChunkLocationDTO("doc:1:v1:c1",30L,1L,1L,"chunk-v1",0,8,"不支持视频输出。","不支持视频输出。",true,true,"READY","PUBLISHED"));
        assertDoesNotThrow(()->planner.verifyCurrent(result,input));
    }
    @Test void budgetTrimmingCannotAuthorizeAChunkThatWasRemoved(){
        var source=context();var bounded=CandidatePlanner.bounded(source,c->c.products().get(0).chunks().isEmpty()?1000:20000);
        assertTrue(bounded.partial());assertTrue(bounded.products().get(0).chunks().isEmpty());
        assertCode("CANDIDATE_SOURCE_UNKNOWN","{\"selections\":{\"1\":{\"productId\":1,\"chunkIds\":[\"doc:1:v1:c1\"]},\"3\":{\"productId\":2,\"chunkIds\":[]}}}",bounded);
    }
    @Test void fourthWiredOptionIsNotExcludedByCatalogOrder(){
        var input=req(new RequirementDetails(100000L,2,List.of(3),"办公",List.of(),List.of("优先有线连接")));
        var products=List.of(product(36,3,29900),product(35,3,8900),product(34,3,9900),product(2,3,3900),product(1,3,7900));
        when(client.list(3,1,20,10001L,2L)).thenReturn(new Products(products,5L));
        for(var p:products)when(client.chunks(p.id(),12,10001L,2L)).thenReturn(new Chunks(List.of(chunk(p.id())),1L,true,List.of()));
        var ctx=planner.prepare(input);assertEquals(5,ctx.products().size());assertTrue(ctx.products().stream().anyMatch(e->e.product().id()==2L));assertFalse(ctx.partial());
    }
    @Test void eachCategoryFitsButWholeMinimumCannotFitFailsBeforeReadingChunks(){
        var input=req(new RequirementDetails(1000L,2,List.of(1,3),"办公",List.of(),List.of(),List.of(new RequirementItem(1,1),new RequirementItem(3,1))));
        when(client.list(1,1,20,10001L,2L)).thenReturn(new Products(List.of(product(1,1,600)),1L));
        when(client.list(3,1,20,10001L,2L)).thenReturn(new Products(List.of(product(2,3,500)),1L));
        assertEquals("CANDIDATE_BUDGET_EMPTY",assertThrows(ModelCallException.class,()->planner.prepare(input)).code());
        verify(client,never()).chunks(anyLong(),anyInt(),anyLong(),anyLong());
    }
    @Test void optionMustLeaveBudgetForOtherRequiredCategories(){
        var input=req(new RequirementDetails(1000L,2,List.of(1,3),"办公",List.of(),List.of(),List.of(new RequirementItem(1,1),new RequirementItem(3,1))));
        when(client.list(1,1,20,10001L,2L)).thenReturn(new Products(List.of(product(1,1,600),product(3,1,400)),2L));
        when(client.list(3,1,20,10001L,2L)).thenReturn(new Products(List.of(product(2,3,500)),1L));
        for(long id:new long[]{2,3})when(client.chunks(id,12,10001L,2L)).thenReturn(new Chunks(List.of(),0L,true,List.of()));
        var ctx=planner.prepare(input);assertEquals(List.of(3L,2L),ctx.products().stream().map(e->e.product().id()).toList());
        verify(client,never()).chunks(eq(1L),anyInt(),anyLong(),anyLong());
    }
    @Test void boundedPoolAlwaysRetainsCheapestEvenWhenLast(){
        var input=req(new RequirementDetails(10000L,1,List.of(3),"办公",List.of(),List.of()));
        List<Product> products=new ArrayList<>();for(long id=1;id<=7;id++){products.add(product(id,3,id==7?100:1000));when(client.chunks(id,12,10001L,2L)).thenReturn(new Chunks(List.of(),0L,true,List.of()));}
        when(client.list(3,1,20,10001L,2L)).thenReturn(new Products(products,7L));
        var ctx=planner.prepare(input);assertTrue(ctx.partial());assertEquals(6,ctx.products().size());assertTrue(ctx.products().stream().anyMatch(e->e.product().id()==7));
    }
    @Test void byteReductionCannotRemoveOnlyAffordableCombination(){
        var input=req(new RequirementDetails(1000L,2,List.of(1,3),"办公",List.of(),List.of(),List.of(new RequirementItem(1,1),new RequirementItem(3,1))));
        var ctx=new CandidatePlanner.Context(input,List.of(new CandidatePlanner.Entry(product(1,1,800),1,List.of()),new CandidatePlanner.Entry(product(2,1,400),1,List.of()),new CandidatePlanner.Entry(product(3,3,900),1,List.of()),new CandidatePlanner.Entry(product(4,3,500),1,List.of())),false);
        var bounded=CandidatePlanner.bounded(ctx,c->c.products().size()*8000);
        assertEquals(Set.of(2L,4L),new HashSet<>(bounded.products().stream().map(e->e.product().id()).toList()));assertTrue(bounded.partial());
    }
    @Test void duplicateSlotKeysAreRejectedInsteadOfLastValueWinning(){
        assertCode("CANDIDATE_OUTPUT_JSON","{\"selections\":{\"1\":{\"productId\":1,\"chunkIds\":[]},\"1\":{\"productId\":5,\"chunkIds\":[]},\"3\":{\"productId\":2,\"chunkIds\":[]}}}",context());
    }
    @Test void unknownAndNoncanonicalSlotsCannotAliasARequiredCategory(){
        for(String key:List.of("01","+1","1.0"," 1","9"))
            assertCode("CANDIDATE_CATEGORY_UNKNOWN","{\"selections\":{\""+key+"\":{\"productId\":1,\"chunkIds\":[]},\"3\":{\"productId\":2,\"chunkIds\":[]}}}",context());
    }
    @Test void oldArrayOrMultipleProductsInOneSlotCannotBeSilentlyConverted(){
        for(String wire:List.of("{\"selections\":[]}","{\"selections\":[{\"productId\":1,\"chunkIds\":[]}]}",
                "{\"selections\":{\"1\":[{\"productId\":1,\"chunkIds\":[]},{\"productId\":5,\"chunkIds\":[]}],\"3\":{\"productId\":2,\"chunkIds\":[]}}}"))
            assertCode("CANDIDATE_OUTPUT_JSON",wire,context());
    }
    @Test void responseKeyOrderDoesNotChangeDemandOrderOrAmounts(){
        var result=planner.parse("{\"selections\":{\"3\":{\"productId\":2,\"chunkIds\":[]},\"1\":{\"productId\":1,\"chunkIds\":[]}}}",context(),mapper);
        assertEquals(List.of(1,3),result.candidate().items().stream().map(CandidateProposal.Item::categoryId).toList());
        assertEquals(2500,result.candidate().totalAmount());
    }
    @Test void nullSlotsAndExtraQuantityAreNotRepaired(){
        assertCode("CANDIDATE_OUTPUT","{\"selections\":{\"1\":null,\"3\":{\"productId\":2,\"chunkIds\":[]}}}",context());
        assertCode("CANDIDATE_OUTPUT_JSON","{\"selections\":{\"1\":{\"productId\":1,\"quantity\":99,\"chunkIds\":[]},\"3\":{\"productId\":2,\"chunkIds\":[]}}}",context());
    }
}
