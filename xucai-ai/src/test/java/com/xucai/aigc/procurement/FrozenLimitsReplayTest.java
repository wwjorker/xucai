package com.xucai.aigc.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 历史失败仅作离线回归，不回写冻结评分，不宣称真实模型已改正。 */
class FrozenLimitsReplayTest {
    final ObjectMapper mapper = DashScopeRequirementAnalyzer.strictMapper(new ObjectMapper());

    @Test void realPositiveConnectionFieldsStillProveAtomicProtocolCondition() throws Exception {
        var fixture=mapper.readTree(getClass().getResourceAsStream("/frozen-limits-replay.json"));
        int checked=0;
        for(var p:fixture.path("products"))if(Set.of(36L,37L,38L).contains(p.path("id").asLong())) {
            var product=mapper.treeToValue(p,CandidateCatalogDTO.Product.class);
            var requirement=new RequirementDetails(50000L,1,List.of(product.categoryId()),"办公",List.of("Unifying接收器"),List.of());
            assertDoesNotThrow(()->CandidateFactGuard.verify(requirement,product));checked++;
        }
        assertEquals(3,checked);
    }

    @Test void savedBundleAndExclusionFailuresAreNowRejected() throws Exception {
        var fixture=mapper.readTree(getClass().getResourceAsStream("/frozen-limits-replay.json"));
        var errors=Map.of("HOLD-E08","MODEL_BUNDLE_QUANTITY_UNSUPPORTED","HOLD-E17","MODEL_CONDITION_OMITTED");
        for(var row:fixture.path("cases")) {
            String id=row.path("id").asText();
            if(!errors.containsKey(id))continue;
            var input=mapper.treeToValue(row.path("requirement"),RequirementDTO.class);
            var result=mapper.treeToValue(row.path("result"),AnalysisResult.class);
            assertEquals(errors.get(id),assertThrows(ModelCallException.class,
                    ()->ExtractedTextGrounding.verify(input,result)).code());
        }
    }

    @Test void actualMissingProtocolProofIsRejectedDuringCandidateParsing() throws Exception {
        var fixture=mapper.readTree(getClass().getResourceAsStream("/frozen-limits-replay.json"));
        var row=fixture.path("cases").get(2);
        assertEquals("HOLD-C06",row.path("id").asText());
        var input=mapper.treeToValue(row.path("requirement"),RequirementDTO.class);
        List<CandidatePlanner.Entry> entries=new ArrayList<>();
        Map<String,Object> selections=new LinkedHashMap<>();
        for(var item:row.path("result").path("candidate").path("items")) {
            CandidateCatalogDTO.Product product=null;
            for(var p:fixture.path("products"))if(p.path("id").asLong()==item.path("productId").asLong())
                product=mapper.treeToValue(p,CandidateCatalogDTO.Product.class);
            assertNotNull(product);
            var chunks=mapper.readerForListOf(CandidateCatalogDTO.Chunk.class).<List<CandidateCatalogDTO.Chunk>>readValue(item.path("evidence"));
            entries.add(new CandidatePlanner.Entry(product,item.path("quantity").asInt(),chunks));
            selections.put(String.valueOf(product.categoryId()),Map.of("productId",product.id(),"chunkIds",chunks.stream().map(CandidateCatalogDTO.Chunk::chunkId).toList()));
        }
        var context=new CandidatePlanner.Context(input,entries,false);
        var planner=new CandidatePlanner(mock(com.xucai.api.client.catalog.CandidateCatalogClient.class));
        String wire=mapper.writeValueAsString(Map.of("selections",selections));
        assertEquals("CANDIDATE_FACT_UNSUPPORTED",assertThrows(ModelCallException.class,
                ()->planner.parse(wire,context,mapper)).code());
    }
}
