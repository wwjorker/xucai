package com.xucai.aigc.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.*;
import com.xucai.api.dto.mall.CandidateCatalogDTO.Product;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CandidateFactGuardTest {
    final ObjectMapper mapper = DashScopeRequirementAnalyzer.strictMapper(new ObjectMapper());
    RequirementDetails requirement(String condition) {
        return new RequirementDetails(50000L,1,List.of(3),"办公",List.of(condition),List.of());
    }
    Product product(long id, String protocol) {
        var specs=mapper.createObjectNode(); if(protocol!=null)specs.put("receiver_protocol",protocol);
        return new Product(id,"任意名称",3,1L,specs,"PUBLISHED",true,new SaleItemDTO(id,9900L,20,true,1L),"可购买");
    }
    @Test void affirmativeRequirementAndExactNegativeFactRejectRegardlessOfId() {
        for(long id:new long[]{34,9876}) for(String condition:List.of("Unifying接收器","必须支持 Logitech Unifying 协议","兼容Unifying","支持Logitech Unifying USB接收器","必须使用Unifying接收器连接"))
            assertEquals("CANDIDATE_FACT_CONFLICT",assertThrows(ModelCallException.class,
                    ()->CandidateFactGuard.verify(requirement(condition),product(id,"Logitech non-Unifying"))).code());
    }
    @Test void unknownOrCompositeProtocolsAreUnsupportedNotConflict() {
        for(String fact:Arrays.asList(null,"USB无线接收器","non-Unifying或其他协议","non-Unifying兼容性待核实"))
            assertEquals("CANDIDATE_FACT_UNSUPPORTED",assertThrows(ModelCallException.class,
                    ()->CandidateFactGuard.verify(requirement("Unifying接收器"),product(9876,fact))).code());
        for(String fact:List.of("Logitech Unifying","Unifying"))
            assertDoesNotThrow(()->CandidateFactGuard.verify(requirement("Unifying接收器"),product(9876,fact)));
    }
    @Test void normalizationAndKeyboardScopeRemainExplicit() {
        var original=product(34," Ｌｏｇｉｔｅｃｈ non‑Unifying ");
        var keyboard=new Product(99L,"键盘",4,1L,original.specJson(),original.status(),true,original.sale(),"可购买");
        var d=new RequirementDetails(50000L,1,List.of(4),"办公",List.of("Unifying接收器"),List.of());
        assertEquals("CANDIDATE_FACT_CONFLICT",assertThrows(ModelCallException.class,()->CandidateFactGuard.verify(d,keyboard)).code());
        assertDoesNotThrow(()->CandidateFactGuard.verify(requirement("Unifying接收器"),keyboard));
        var monitor=new Product(99L,"显示器",1,1L,original.specJson(),original.status(),true,original.sale(),"可购买");
        var monitorRequirement=new RequirementDetails(50000L,1,List.of(1),"办公",List.of("Unifying接收器"),List.of());
        assertDoesNotThrow(()->CandidateFactGuard.verify(monitorRequirement,monitor));
    }
    @Test void explicitProtocolConflictWinsOverConnectionPositive() {
        var p=product(9876,"non-Unifying");
        ((com.fasterxml.jackson.databind.node.ObjectNode)p.specJson()).putArray("connections").add("Logitech Unifying USB接收器");
        assertEquals("CANDIDATE_FACT_CONFLICT",assertThrows(ModelCallException.class,
                ()->CandidateFactGuard.verify(requirement("Unifying接收器"),p)).code());
    }
    @Test void negationAlternativesAndUnscopedMultipleCategoriesAreNotMisread() {
        for(String condition:List.of("不要求Unifying","无需Unifying接收器","non-Unifying接收器","Unifying或蓝牙","Unifying也可以","键盘必须Unifying","USB无线接收器"))
            assertDoesNotThrow(()->CandidateFactGuard.verify(requirement(condition),product(34,"non-Unifying")));
        var multiple=new RequirementDetails(50000L,2,List.of(3,4),"办公",List.of("Unifying接收器"),List.of());
        assertDoesNotThrow(()->CandidateFactGuard.verify(multiple,product(34,"non-Unifying")));
        var preferred=new RequirementDetails(50000L,1,List.of(3),"办公",List.of(),List.of("Unifying接收器"));
        assertDoesNotThrow(()->CandidateFactGuard.verify(preferred,product(34,"non-Unifying")));
    }
    @Test void actualFailedSelectionIsRejectedButSameProductPositiveControlSurvives() throws Exception {
        var fixture=mapper.readTree(getClass().getResourceAsStream("/development-grounding-replay.json"));
        var planner=new CandidatePlanner(mock(com.xucai.api.client.catalog.CandidateCatalogClient.class));
        for(var row:fixture.path("cases")) {
            if(!Set.of("DEV-C13","DEV-C14","DEV-C16").contains(row.path("id").asText()))continue;
            var input=mapper.treeToValue(row.path("requirement"),RequirementDTO.class);
            List<CandidatePlanner.Entry> entries=new ArrayList<>();Map<String,Object> selections=new LinkedHashMap<>();
            for(var item:row.path("result").path("candidate").path("items")) {
                Product product=null;
                for(var p:fixture.path("products"))if(p.path("id").asLong()==item.path("productId").asLong())product=mapper.treeToValue(p,Product.class);
                assertNotNull(product);
                var chunks=mapper.readerForListOf(CandidateCatalogDTO.Chunk.class).<List<CandidateCatalogDTO.Chunk>>readValue(item.path("evidence"));
                entries.add(new CandidatePlanner.Entry(product,item.path("quantity").asInt(),chunks));
                selections.put(String.valueOf(product.categoryId()),Map.of("productId",product.id(),"chunkIds",chunks.stream().map(CandidateCatalogDTO.Chunk::chunkId).toList()));
            }
            var context=new CandidatePlanner.Context(input,entries,false);String wire=mapper.writeValueAsString(Map.of("selections",selections));
            if(row.path("id").asText().equals("DEV-C13"))
                assertEquals("CANDIDATE_FACT_CONFLICT",assertThrows(ModelCallException.class,()->planner.parse(wire,context,mapper)).code());
            else assertEquals("REFERENCE",planner.parse(wire,context,mapper).candidate().status());
        }
    }
}
