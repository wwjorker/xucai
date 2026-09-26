package com.xucai.trade.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.*;
import com.xucai.trade.domain.dto.PlanReviewForm;
import com.xucai.common.exceptions.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PlanReviewRulesTest {
    private PlanDraftRules.Snapshot snapshot(){
        var req=new RequirementDTO(1L,2L,"测试","需要2个鼠标",new RequirementDetails(10000L,2,List.of(3),"办公",List.of("有线连接"),List.of(),List.of(new RequirementItem(3,2))),1L,"OPEN","now","now");
        var e=new PlanDraftRules.Evidence(9L,1L,"资料","https://example.com","USB有线连接。线长1.8米。","USB有线连接","待核实","UNKNOWN",null);
        var item=new PlanDraftRules.Item(1L,"鼠标",3,new ObjectMapper().createObjectNode().put("connection","USB"),1L,2,3000L,1L,List.of(e));
        return new PlanDraftRules.Snapshot(req,List.of(item),List.of());
    }
    private PlanReviewForm.Check check(String key,String verdict){return new PlanReviewForm.Check(key,verdict,"此商品的USB有线连接可支持所述办公使用方式",List.of(new PlanReviewForm.Reference(1L,9L)));}
    private PlanReviewForm form(List<PlanReviewForm.Check> checks){return new PlanReviewForm(UUID.randomUUID().toString(),"人工核实说明",checks);}
    private Map<Long,SaleItemDTO> sales(){return Map.of(1L,new SaleItemDTO(1L,3000L,5,true,1L));}
    @Test void conditionsComeFromFrozenRequirement(){assertEquals(List.of("usage","mustHave:0"),PlanReviewRules.conditions(snapshot()).stream().map(PlanReviewRules.Condition::key).toList());}
    @Test void acceptedReviewKeepsMerchantScopeAndExactQuote(){var r=PlanReviewRules.review(snapshot(),form(List.of(check("usage","MET"),check("mustHave:0","MET"))));assertEquals("MERCHANT",r.checks().get(0).assessedBy());assertEquals("USB有线连接",r.checks().get(0).references().get(0).quote());}
    @Test void omittedDuplicateAndUnknownKeysRejected(){for(var checks:List.of(List.of(check("usage","MET")),List.of(check("usage","MET"),check("usage","MET")),List.of(check("usage","MET"),check("mustHave:3","MET"))))assertThrows(BusinessException.class,()->PlanReviewRules.review(snapshot(),form(checks)));}
    @Test void unknownAndConflictCannotPass(){for(String verdict:List.of("UNKNOWN","CONFLICT"))assertThrows(BusinessException.class,()->PlanReviewRules.review(snapshot(),form(List.of(check("usage","MET"),check("mustHave:0",verdict)))));}
    @Test void arbitraryOrEmptyReferencesCannotPass(){for(var refs:List.of(List.<PlanReviewForm.Reference>of(),List.of(new PlanReviewForm.Reference(2L,9L)),List.of(new PlanReviewForm.Reference(1L,8L))))assertThrows(BusinessException.class,()->PlanReviewRules.review(snapshot(),form(List.of(check("usage","MET"),new PlanReviewForm.Check("mustHave:0","MET","说明",refs)))));}
    @Test void budgetAndCategoryCountsAreHardConditions(){assertTrue(PlanReviewRules.business(snapshot(),sales()).isEmpty());var old=snapshot();var i=old.items().get(0);var changed=new PlanDraftRules.Item(1L,"鼠标",4,i.specJson(),1L,2,6000L,1L,i.evidence());var r=PlanReviewRules.business(new PlanDraftRules.Snapshot(old.requirement(),List.of(changed),List.of()),Map.of(1L,new SaleItemDTO(1L,6000L,5,true,1L)));assertTrue(r.stream().anyMatch(x->x.contains("预算")));assertTrue(r.stream().anyMatch(x->x.contains("分类")));}
    @Test void saleChangesAndStockShortageBlock(){var r=PlanReviewRules.business(snapshot(),Map.of(1L,new SaleItemDTO(1L,3001L,1,true,2L)));assertEquals(2,r.size());assertFalse(PlanReviewRules.business(snapshot(),Map.of()).isEmpty());}
    @Test void correctedSpecAndRetiredDocumentBlock(){var i=snapshot().items().get(0);var p=new CatalogSnapshotDTO(1L,"鼠标",3,i.specJson(),2L,"PUBLISHED",List.of());var r=PlanReviewRules.catalog(snapshot(),Map.of(1L,p));assertTrue(r.stream().anyMatch(x->x.contains("规格")));assertTrue(r.stream().anyMatch(x->x.contains("资料")));}
}
