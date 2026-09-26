package com.xucai.aigc.procurement.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.aigc.procurement.ModelCallException;
import com.xucai.api.client.catalog.CandidateCatalogClient;
import com.xucai.api.dto.mall.*;
import com.xucai.api.dto.mall.CandidateCatalogDTO.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CandidateVectorRetrieverTest {
    final ObjectMapper mapper=new ObjectMapper();
    final CandidateCatalogClient catalog=mock(CandidateCatalogClient.class);
    final EmbeddingClient model=mock(EmbeddingClient.class);
    final VectorIndex vectors=mock(VectorIndex.class);
    final RetrievalStore store=mock(RetrievalStore.class);
    final CandidateVectorRetriever retriever=new CandidateVectorRetriever(catalog,model,vectors,store,mapper);
    final String id="00000000-0000-4000-8000-000000000010",text="不支持视频输出。";
    final RequirementDTO req=new RequirementDTO(48L,10001L,"private title","private raw",new RequirementDetails(50000L,2,List.of(3,4),"办公",List.of(),List.of("有线"),List.of(new RequirementItem(3,1),new RequirementItem(4,1))),1L,"OPEN","now","now");
    Product product(long id){return new Product(id,"公开型号",(int)id+2,1L,mapper.createObjectNode(),"PUBLISHED",true,new SaleItemDTO(id,100L,20,true,1L),"可购买");}
    Chunk chunk(long id){return new Chunk("doc:"+id+":v1:c1",id,id,1L,"chunk-v1",0,text.length(),text,"规格","https://example.com");}
    IndexSnapshot snapshot(long id){return new IndexSnapshot(id,"公开型号",(int)id+2,1L,List.of(new DocumentManifest(id,1L,RetrievalRules.hash(text),"chunk-v1",1)),List.of(chunk(id)));}
    @BeforeEach void setup(){
        when(vectors.key(anyString(),anyString())).thenAnswer(a->a.getArgument(0)+":"+a.getArgument(1));
        when(vectors.complete(anyString(),anyList())).thenReturn(true);
        when(model.embed(anyString(),eq("query"),anyList())).thenReturn(List.of(new float[1024]));
        for(long p:List.of(1L,2L)) {
            var s=snapshot(p);String hash=RetrievalRules.hash(RetrievalRules.json(s,mapper));
            when(store.index(p)).thenReturn(Optional.of(new RetrievalStore.Index("g"+p,hash,s)));
            when(catalog.indexSnapshot(p,10001L,2L)).thenReturn(s);
            when(vectors.search(eq(List.of("g"+p)),any(),eq(3))).thenReturn(List.of(new VectorIndex.Hit("g"+p+":"+chunk(p).chunkId(),.1)));
            when(catalog.locate(p,chunk(p).chunkId(),10001L,2L)).thenReturn(new DocChunkLocationDTO(chunk(p).chunkId(),p,p,1L,"chunk-v1",0,text.length(),text,text,true,true,"READY","PUBLISHED"));
        }
    }
    @AfterEach void clearInterrupt(){Thread.interrupted();}
    @Test void oneQueryPerRunAndIndependentPerProductRanksUseCustomerIdentity(){
        var result=retriever.retrieve(id,req,List.of(product(1),product(2)));
        assertEquals(Set.of(1L,2L),result.chunks().keySet());assertEquals(text,result.chunks().get(1L).get(0).text());
        assertEquals(2,result.trace().products().size());assertEquals(id,result.trace().operationId());
        verify(model,times(1)).embed(eq(id+":0"),eq("query"),argThat(q->!q.get(0).contains("private")&&!q.get(0).contains("10001")));
        verify(vectors).search(eq(List.of("g1")),any(),eq(3));verify(vectors).search(eq(List.of("g2")),any(),eq(3));
        verify(catalog,never()).indexSnapshot(anyLong(),anyLong(),eq(1L));verify(catalog,never()).locate(anyLong(),anyString(),anyLong(),eq(1L));
    }
    @Test void anyMissingIndexPreventsAllModelCalls(){when(store.index(2L)).thenReturn(Optional.empty());assertThrows(ModelCallException.class,()->retriever.retrieve(id,req,List.of(product(1),product(2))));verifyNoInteractions(model);}
    @Test void incompleteVectorsPreventSpend(){when(vectors.complete(eq("g2"),anyList())).thenReturn(false);assertThrows(ModelCallException.class,()->retriever.retrieve(id,req,List.of(product(1),product(2))));verifyNoInteractions(model);}
    @Test void changedSpecPreventsSpend(){var p=product(1);var changed=new Product(p.id(),p.name(),p.categoryId(),2L,p.specJson(),p.status(),p.priceAvailable(),p.sale(),p.purchaseMessage());assertThrows(ModelCallException.class,()->retriever.retrieve(id,req,List.of(changed)));verifyNoInteractions(model);}
    @Test void crossProductAndInvalidDistanceRejectWholeResult(){for(var hit:List.of(new VectorIndex.Hit("g2:"+chunk(2).chunkId(),.1),new VectorIndex.Hit("g1:"+chunk(1).chunkId(),Double.NaN))){when(vectors.search(eq(List.of("g1")),any(),eq(3))).thenReturn(List.of(hit));assertThrows(ModelCallException.class,()->retriever.retrieve(id,req,List.of(product(1))));}}
    @Test void noHitsIsIntegrityFailureNotNoEvidence(){when(vectors.search(anyList(),any(),anyInt())).thenReturn(List.of());assertThrows(ModelCallException.class,()->retriever.retrieve(id,req,List.of(product(1))));}
    @Test void changedOriginalRejectsResult(){when(catalog.locate(anyLong(),anyString(),anyLong(),anyLong())).thenReturn(null);assertThrows(ModelCallException.class,()->retriever.retrieve(id,req,List.of(product(1))));}
    @Test void changedAfterSearchRejectsResult(){var s=snapshot(1);when(catalog.indexSnapshot(1L,10001L,2L)).thenReturn(s,new IndexSnapshot(1L,"改版名称",3,1L,s.documents(),s.chunks()));assertThrows(ModelCallException.class,()->retriever.retrieve(id,req,List.of(product(1))));}
    @Test void cancellationAfterEmbeddingStopsBeforeSearching(){when(model.embed(anyString(),anyString(),anyList())).thenAnswer(a->{Thread.currentThread().interrupt();return List.of(new float[1024]);});assertEquals("MODEL_CANCELED",assertThrows(ModelCallException.class,()->retriever.retrieve(id,req,List.of(product(1)))).code());verify(vectors,never()).search(anyList(),any(),anyInt());}
}
