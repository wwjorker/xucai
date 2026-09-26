package com.xucai.aigc.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.client.catalog.CandidateCatalogClient;
import com.xucai.api.dto.mall.*;
import com.xucai.api.dto.mall.CandidateCatalogDTO.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CandidatePoolFixtureTest {
    @Test void realPublicCatalogKeepsWiredMouseAndKeyboardWithinWireBudget()throws Exception {
        var builder=new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder();
        new com.xucai.common.autoconfigure.mvc.JsonConfig().jackson2ObjectMapperBuilderCustomizer().customize(builder);
        ObjectMapper mapper=builder.build();
        var fixture=mapper.readTree(getClass().getResourceAsStream("/candidate-pool-public.json"));
        var catalog=mock(CandidateCatalogClient.class);
        for(int category:new int[]{3,4})when(catalog.list(category,1,20,10001L,2L))
                .thenReturn(mapper.treeToValue(fixture.path("categories").path(String.valueOf(category)),Products.class));
        fixture.path("chunks").fields().forEachRemaining(e->{
            try {when(catalog.chunks(Long.parseLong(e.getKey()),12,10001L,2L)).thenReturn(mapper.treeToValue(e.getValue(),Chunks.class));}
            catch(Exception ex){throw new IllegalStateException(ex);}
        });
        var requirement=new RequirementDTO(48L,10001L,"演示需求","原文不发送供应商",new RequirementDetails(100000L,3,List.of(3,4),"办公文档处理",List.of(),List.of("优先有线连接"),List.of(new RequirementItem(3,2),new RequirementItem(4,1))),1L,"OPEN","now","now");
        var planner=new CandidatePlanner(catalog);var prepared=planner.prepare(requirement);
        java.util.function.ToIntFunction<CandidatePlanner.Context> bytes=c->DashScopeRequirementAnalyzer.requestBody(CandidatePlanner.PROMPT,CandidatePlanner.modelInput(c),mapper).getBytes(StandardCharsets.UTF_8).length;
        var bounded=CandidatePlanner.bounded(prepared,bytes);
        assertEquals(8,prepared.products().size());assertTrue(bounded.products().stream().anyMatch(e->e.product().id()==2L));assertTrue(bounded.products().stream().anyMatch(e->e.product().id()==3L));
        assertTrue(bytes.applyAsInt(bounded)<=16384);
        var wire=DashScopeRequirementAnalyzer.requestBody(CandidatePlanner.PROMPT,CandidatePlanner.modelInput(bounded),mapper);
        assertFalse(wire.contains("10001"));assertFalse(wire.contains("原文不发送供应商"));assertTrue(wire.contains("USB 有线"));
        for(var e:bounded.products())for(var c:e.chunks())assertTrue(prepared.products().stream().filter(p->p.product().id().equals(e.product().id())).findFirst().orElseThrow().chunks().contains(c));
        // 手写模型响应仅验证后处理合同，不算一次模型选品成功。
        var result=planner.parse("{\"selections\":{\"3\":{\"productId\":2,\"chunkIds\":[]},\"4\":{\"productId\":3,\"chunkIds\":[]}}}",bounded,DashScopeRequirementAnalyzer.strictMapper(mapper));
        assertEquals(14700,result.candidate().totalAmount());assertTrue(result.candidate().items().stream().allMatch(i->i.verdict().equals("UNKNOWN")));
        var report=Map.of("source","2026-09-16 anonymous catalog fixture","promptVersion",CandidatePlanner.VERSION,"beforeBytes",bytes.applyAsInt(prepared),"afterBytes",bytes.applyAsInt(bounded),"preparedProducts",prepared.products().size(),"sentProductIds",bounded.products().stream().map(e->e.product().id()).toList(),"partialPool",bounded.partial(),"wiredCombinationCents",14700,"realModelCalls",0);
        Files.createDirectories(Path.of("target"));mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/candidate-pool-check.json").toFile(),report);
    }
}
