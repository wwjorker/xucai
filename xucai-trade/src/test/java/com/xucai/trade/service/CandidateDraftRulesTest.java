package com.xucai.trade.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.*;
import com.xucai.api.dto.mall.CandidateCatalogDTO.Chunk;
import com.xucai.common.exceptions.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CandidateDraftRulesTest {
    private final String text="支持USB连接。不支持自动双面打印。配有电源线。";
    private RequirementDTO req(long id,long user,long revision,String status){return new RequirementDTO(id,user,"测试","原文",new RequirementDetails(20000L,2,List.of(2),"打印",List.of(),List.of(),List.of(new RequirementItem(2,2))),revision,status,"now","now");}
    private CatalogSnapshotDTO product(String content){return new CatalogSnapshotDTO(1L,"打印机",2,new ObjectMapper().createObjectNode(),3L,"PUBLISHED",List.of(new CatalogSnapshotDTO.Document(9L,1L,"说明","https://example.com",content)));}
    private Chunk chunk(int n,int start,int end,String content){return new Chunk("doc:9:v1:c"+n,1L,9L,1L,"chunk-v1",start,end,content.substring(start,end),"说明","https://example.com");}
    private CandidateRunDTO run(List<Chunk> chunks){return new CandidateRunDTO("71974a36-4832-4bbc-aa0c-35bce2a95e65",8L,1L,2L,"model","candidate-reference-v3",new CandidateRunDTO.Candidate("REFERENCE",List.of(new CandidateRunDTO.Item(1L,"打印机",2,3L,4L,5000L,2,chunks,"UNKNOWN")),10000,6,true,false,"参考"));}
    private Map<String,DocChunkLocationDTO> locations(List<Chunk> chunks,String content){Map<String,DocChunkLocationDTO> m=new HashMap<>();for(var c:chunks)m.put(c.chunkId(),new DocChunkLocationDTO(c.chunkId(),1L,9L,1L,c.ruleVersion(),c.charStart(),c.charEnd(),c.text(),content,true,true,"READY","PUBLISHED"));return m;}
    @Test void mergePreservesInterveningNegativeAndOriginalProvenance()throws Exception {
        var chunks=List.of(chunk(1,0,8,text),chunk(3,18,text.length(),text));var run=run(chunks);
        var form=CandidateDraftRules.form(run,req(8,2,1,"OPEN"),Map.of(1L,product(text)),locations(chunks,text));
        var e=form.items().get(0).evidence().get(0);assertEquals(text,e.quote());assertTrue(e.quote().contains("不支持自动双面"));assertNull(e.chunk());
        var item=PlanDraftRules.item(form.items().get(0),product(text),new SaleItemDTO(1L,5000L,20,true,4L));assertEquals("UNKNOWN",item.evidence().get(0).verdict());
        var snapshot=new PlanDraftRules.Snapshot(req(8,2,1,"OPEN"),List.of(item),List.of(),run);
        var json=new ObjectMapper();var copy=json.readValue(json.writeValueAsString(snapshot),PlanDraftRules.Snapshot.class);
        assertEquals(chunks,copy.aiOrigin().candidate().items().get(0).evidence());
    }
    @Test void retrievalProvenanceSurvivesDraftAndPublishedSnapshotRoundTrip()throws Exception {
        var old=run(List.of(chunk(1,0,8,text)));var c=old.candidate();
        var trace=new CandidateRetrievalDTO("candidate-vector-v1","text-embedding-v4",1024,old.runId(),"hash",3,List.of(new CandidateRetrievalDTO.ProductScope(1,"g","hash",List.of(new CandidateRetrievalDTO.Hit(c.items().get(0).evidence().get(0).chunkId(),1,.2)),List.of(c.items().get(0).evidence().get(0).chunkId()))));
        var run=new CandidateRunDTO(old.runId(),old.requirementId(),old.requirementRevision(),old.userId(),old.model(),"candidate-reference-v5",new CandidateRunDTO.Candidate(c.status(),c.items(),c.totalAmount(),c.consideredProducts(),true,true,c.notice(),trace));
        var form=CandidateDraftRules.form(run,req(8,2,1,"OPEN"),Map.of(1L,product(text)),locations(c.items().get(0).evidence(),text));
        var item=PlanDraftRules.item(form.items().get(0),product(text),new SaleItemDTO(1L,5000L,20,true,4L));
        var snapshot=new PlanDraftRules.Snapshot(req(8,2,1,"OPEN"),List.of(item),List.of(),run);
        var builder=new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder();new com.xucai.common.autoconfigure.mvc.JsonConfig().jackson2ObjectMapperBuilderCustomizer().customize(builder);var mapper=builder.build();
        assertEquals(trace,mapper.readValue(mapper.writeValueAsString(snapshot),PlanDraftRules.Snapshot.class).aiOrigin().candidate().retrieval());
        assertEquals("UNKNOWN",item.evidence().get(0).verdict());
    }
    @Test void singleChunkKeepsVerifiedOffsetAndHash(){var chunks=List.of(chunk(2,8,18,text));var form=CandidateDraftRules.form(run(chunks),req(8,2,1,"OPEN"),Map.of(1L,product(text)),locations(chunks,text));var item=PlanDraftRules.item(form.items().get(0),product(text),new SaleItemDTO(1L,5000L,20,true,4L));assertEquals(PlanDraftRules.sha256(chunks.get(0).text()),item.evidence().get(0).chunk().quoteHash());}
    @Test void mergedSpanOverLimitFailsWithoutDroppingChunks(){String s="首。"+"不支持。".repeat(260)+"尾。";var chunks=List.of(chunk(1,0,2,s),chunk(3,s.length()-2,s.length(),s));assertThrows(BusinessException.class,()->CandidateDraftRules.form(run(chunks),req(8,2,1,"OPEN"),Map.of(1L,product(s)),locations(chunks,s)));}
    @Test void wrongOwnerRequirementRevisionOrClosedCannotAdopt(){var chunks=List.of(chunk(1,0,8,text));for(var r:List.of(req(9,2,1,"OPEN"),req(8,3,1,"OPEN"),req(8,2,2,"OPEN"),req(8,2,1,"CLOSED")))assertThrows(BusinessException.class,()->CandidateDraftRules.form(run(chunks),r,Map.of(1L,product(text)),locations(chunks,text)));}
    @Test void missingCurrentChunkAndChangedDocumentCannotAdopt(){var chunks=List.of(chunk(1,0,8,text));assertThrows(BusinessException.class,()->CandidateDraftRules.form(run(chunks),req(8,2,1,"OPEN"),Map.of(1L,product(text)),Map.of()));assertThrows(BusinessException.class,()->CandidateDraftRules.form(run(chunks),req(8,2,1,"OPEN"),Map.of(1L,product(text+"更正")),locations(chunks,text)));}
    @Test void duplicatedChunksRejected(){var c=chunk(1,0,8,text);assertThrows(BusinessException.class,()->CandidateDraftRules.form(run(List.of(c,c)),req(8,2,1,"OPEN"),Map.of(1L,product(text)),locations(List.of(c),text)));}
    @Test void olderManualSnapshotStillDecodes()throws Exception {assertNull(new ObjectMapper().readValue("{\"requirement\":null,\"items\":[],\"warnings\":[]}",PlanDraftRules.Snapshot.class).aiOrigin());}
}
