package com.xucai.trade.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.*;
import com.xucai.trade.domain.dto.PlanDraftForm;
import com.xucai.common.exceptions.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PlanDraftRulesTest {
    private CatalogSnapshotDTO product(){return new CatalogSnapshotDTO(1L,"鼠标",3,new ObjectMapper().createObjectNode().put("connection","USB"),2L,"PUBLISHED",List.of(new CatalogSnapshotDTO.Document(9L,3L,"资料","https://example.com","USB连接，线长1.8米。")));}
    private SaleItemDTO sale(){return new SaleItemDTO(1L,3929L,1,true,4L);}
    private PlanDraftForm.Item form(List<PlanDraftForm.Evidence> e){return new PlanDraftForm.Item(1L,2L,4L,2,e);}
    @Test void serverSnapshotAndIntegerCents(){var item=PlanDraftRules.item(form(List.of()),product(),sale());assertEquals(7858L,PlanDraftRules.total(List.of(item)));assertEquals("鼠标",item.name());}
    @Test void existingUnrelatedQuoteRemainsUnknown(){var item=PlanDraftRules.item(form(List.of(new PlanDraftForm.Evidence(9L,3L,"线长1.8米","认为适合静音办公，但尚未核实",null))),product(),sale());assertEquals("UNKNOWN",item.evidence().get(0).verdict());assertEquals("USB连接，线长1.8米。",item.evidence().get(0).content());}
    @Test void foreignDocumentAndOldVersionRejected(){for(var e:List.of(new PlanDraftForm.Evidence(8L,3L,"USB","说明",null),new PlanDraftForm.Evidence(9L,2L,"USB","说明",null)))assertThrows(BusinessException.class,()->PlanDraftRules.item(form(List.of(e)),product(),sale()));}
    @Test void fabricatedAndDuplicateQuoteRejected(){var e=new PlanDraftForm.Evidence(9L,3L,"无线连接","说明",null);assertThrows(BusinessException.class,()->PlanDraftRules.item(form(List.of(e)),product(),sale()));var real=new PlanDraftForm.Evidence(9L,3L,"USB","说明",null);assertThrows(BusinessException.class,()->PlanDraftRules.item(form(List.of(real,real)),product(),sale()));}
    @Test void staleSpecAndSaleRejected(){assertThrows(BusinessException.class,()->PlanDraftRules.item(new PlanDraftForm.Item(1L,1L,4L,2,List.of()),product(),sale()));assertThrows(BusinessException.class,()->PlanDraftRules.item(new PlanDraftForm.Item(1L,2L,3L,2,List.of()),product(),sale()));}
    @Test void missingOrStoppedSaleRejected(){assertThrows(BusinessException.class,()->PlanDraftRules.item(form(List.of()),product(),null));assertThrows(BusinessException.class,()->PlanDraftRules.item(form(List.of()),product(),new SaleItemDTO(1L,3929L,1,false,4L)));}
    private PlanDraftForm.Chunk ref(int start,int end){return new PlanDraftForm.Chunk("doc:9:v3:c1","chunk-v1",start,end);}
    private DocChunkLocationDTO location(String id,String rule,boolean current) {
        return new DocChunkLocationDTO(id,1L,9L,3L,rule,6,12,"线长1.8米",product().documents().get(0).content(),true,current,"READY","PUBLISHED");
    }
    @Test void fabricatedChunkNumberRuleOrRetiredChunkCannotClaimVerifiedProvenance() {
        var e=new PlanDraftForm.Evidence(9L,3L,"线长1.8米","说明",ref(6,12));
        assertDoesNotThrow(()->PlanDraftRules.verifyChunkSource(e,product(),location("doc:9:v3:c1","chunk-v1",true)));
        for(var actual:List.of(location("doc:9:v3:c2","chunk-v1",true),location("doc:9:v3:c1","chunk-v2",true),location("doc:9:v3:c1","chunk-v1",false)))
            assertThrows(BusinessException.class,()->PlanDraftRules.verifyChunkSource(e,product(),actual));
        assertThrows(BusinessException.class,()->PlanDraftRules.verifyChunkSource(e,product(),null));
    }
    @Test void chunkReferenceIsFrozenWithOffsetsAndQuoteHash(){
        // 资料正文 "USB连接，线长1.8米。" 的下标 6..12 正好是 "线长1.8米"
        var e=new PlanDraftForm.Evidence(9L,3L,"线长1.8米","从资料片段选入",ref(6,12));
        var saved=PlanDraftRules.item(form(List.of(e)),product(),sale()).evidence().get(0).chunk();
        assertEquals("doc:9:v3:c1",saved.chunkId());
        assertEquals("chunk-v1",saved.ruleVersion());
        assertEquals(6,saved.charStart());assertEquals(12,saved.charEnd());
        assertEquals(PlanDraftRules.sha256("线长1.8米"),saved.quoteHash());
        assertEquals("DRAFT_TIME_CONTENT",saved.verifiedAgainst());
        assertEquals("UNKNOWN",PlanDraftRules.item(form(List.of(e)),product(),sale()).evidence().get(0).verdict());
    }
    @Test void handwrittenQuoteWithoutChunkStillWorks(){
        var saved=PlanDraftRules.item(form(List.of(new PlanDraftForm.Evidence(9L,3L,"USB连接","手工摘录",null))),product(),sale()).evidence().get(0);
        assertNull(saved.chunk());
        assertEquals("USB连接",saved.quote());
    }
    @Test void chunkOffsetsMustReproduceTheQuoteExactly(){
        // 偏移指向别处、越界、或首尾颠倒，都不能保存成"有出处"的引用
        for(var bad:List.of(ref(0,6),ref(6,99),ref(12,6)))
            assertThrows(BusinessException.class,()->PlanDraftRules.item(form(List.of(new PlanDraftForm.Evidence(9L,3L,"线长1.8米","说明",bad))),product(),sale()));
    }
    @Test void chunkIdMustBelongToTheReferencedDocumentVersion(){
        for(var id:List.of("doc:8:v3:c1","doc:9:v2:c1","doc:9:v3:c","doc:9:v3:cx","doc:9:v3:c01","chunk-1"))
            assertThrows(BusinessException.class,()->PlanDraftRules.item(form(List.of(
                new PlanDraftForm.Evidence(9L,3L,"线长1.8米","说明",new PlanDraftForm.Chunk(id,"chunk-v1",6,12)))),product(),sale()));
    }
    @Test void warningsDoNotPromoteDraftToVerified(){var req=new RequirementDTO(1L,2L,"测试","原文",new RequirementDetails(5000L,3,List.of(3),"办公",List.of("静音"),List.of(),List.of(new RequirementItem(3,3))),1L,"OPEN","now","now");var item=PlanDraftRules.item(form(List.of()),product(),sale());var w=PlanDraftRules.warnings(req,List.of(item),Map.of(1L,sale()));assertTrue(w.stream().anyMatch(s->s.contains("超过")));assertTrue(w.stream().anyMatch(s->s.contains("不一致")));assertTrue(w.stream().anyMatch(s->s.contains("库存")));assertTrue(w.stream().anyMatch(s->s.contains("尚未附")));assertTrue(w.get(0).contains("不能"));}
}
