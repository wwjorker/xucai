package com.xucai.aigc.procurement.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.client.catalog.CandidateCatalogClient;
import com.xucai.api.dto.mall.CandidateCatalogDTO.*;
import com.xucai.api.dto.mall.DocChunkLocationDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetrievalServiceTest {
    static final String ID="00000000-0000-4000-8000-000000000001",OLD="00000000-0000-4000-8000-000000000002";
    final ObjectMapper mapper=new ObjectMapper();final CandidateCatalogClient catalog=mock(CandidateCatalogClient.class);final EmbeddingClient model=mock(EmbeddingClient.class);final VectorIndex vectors=mock(VectorIndex.class);final RetrievalStore store=mock(RetrievalStore.class);
    final RetrievalService service=new RetrievalService(catalog,model,vectors,store,mapper);
    final String text="不支持自动双面打印。";
    final Chunk chunk=new Chunk("doc:1:v1:c1",1L,1L,1L,"chunk-v1",0,text.length(),text,"官方说明","https://example.com/spec");
    final DocumentManifest doc=new DocumentManifest(1L,1L,RetrievalRules.hash(text),"chunk-v1",1);
    final IndexSnapshot snapshot=new IndexSnapshot(1L,"打印机",2,1L,List.of(doc),List.of(chunk));
    String hash(){return RetrievalRules.hash(RetrievalRules.json(snapshot,mapper));}
    @BeforeEach void setup(){when(store.begin(eq(ID),eq(1L),anyString(),anyString())).thenReturn(true);when(store.begin(eq(ID),eq(1L),anyString(),anyString(),anySet())).thenReturn(true);when(catalog.indexSnapshot(1L,1L,1L)).thenReturn(snapshot);when(vectors.complete(anyString(),anyList())).thenReturn(true);when(vectors.key(anyString(),anyString())).thenReturn("key");when(model.embed(anyString(),anyString(),anyList())).thenReturn(List.of(new float[1024]));}
    void indexed(){when(store.index(1L)).thenReturn(Optional.of(new RetrievalStore.Index(OLD,hash(),snapshot)));}
    @Test void newGenerationOnlyPublishedAfterAllWritesAndCurrentCheck(){service.build(ID,1,1);var order=inOrder(model,vectors,store);order.verify(model).embed(ID+":0","document",List.of(text));order.verify(vectors).put(eq(ID),eq(chunk.chunkId()),any());order.verify(vectors).complete(ID,List.of(chunk.chunkId()));order.verify(store).publish(eq(ID),eq(snapshot),eq(hash()),any());verify(catalog,times(2)).indexSnapshot(1L,1L,1L);}
    @Test void partialVectorWriteCannotPublish(){doThrow(new RuntimeException()).when(vectors).put(anyString(),anyString(),any());service.build(ID,1,1);verify(store,never()).publish(anyString(),any(),anyString(),any());verify(store).failed(ID,"RETRIEVAL_UNAVAILABLE");}
    @Test void incompleteCountCannotPublish(){when(vectors.complete(eq(ID),anyList())).thenReturn(false);service.build(ID,1,1);verify(store,never()).publish(anyString(),any(),anyString(),any());verify(store).failed(ID,"RETRIEVAL_INCOMPLETE");}
    @Test void changedSnapshotCannotPublish(){when(catalog.indexSnapshot(1L,1L,1L)).thenReturn(snapshot,new IndexSnapshot(1L,"新名称",2,2L,List.of(doc),List.of(chunk)));service.build(ID,1,1);verify(store).failed(ID,"RETRIEVAL_STALE");verify(store,never()).publish(anyString(),any(),anyString(),any());}
    @Test void exactReadyIndexReusesWithoutModel(){indexed();service.build(ID,1,1);verifyNoInteractions(model);verify(store,never()).publish(anyString(),any(),anyString(),any());verify(store).done(eq(ID),argThat(x->Boolean.TRUE.equals(((Map<?,?>)x).get("reused"))));}
    @Test void replayNeverStartsAgain(){when(store.begin(anyString(),anyLong(),anyString(),anyString())).thenReturn(false);when(store.begin(anyString(),anyLong(),anyString(),anyString(),anySet())).thenReturn(false);service.build(ID,1,1);service.search(ID,List.of(1L),"双面打印",3,1);verifyNoInteractions(model,catalog,vectors);}
    @Test void unbuiltSearchDoesNotSpend(){service.search(ID,List.of(1L),"双面打印",3,1);verifyNoInteractions(model);verify(store).done(eq(ID),argThat(x->"NOT_BUILT".equals(((Map<?,?>)x).get("status"))));}
    @Test void staleSearchDoesNotSpend(){when(store.index(1L)).thenReturn(Optional.of(new RetrievalStore.Index(OLD,"bad",snapshot)));service.search(ID,List.of(1L),"双面打印",3,1);verifyNoInteractions(model);verify(store).done(eq(ID),argThat(x->"STALE".equals(((Map<?,?>)x).get("status"))));}
    @Test void missingVectorsDoNotSpend(){indexed();when(vectors.complete(eq(OLD),anyList())).thenReturn(false);service.search(ID,List.of(1L),"双面打印",3,1);verifyNoInteractions(model);verify(store).done(eq(ID),argThat(x->"INCOMPLETE".equals(((Map<?,?>)x).get("status"))));}
    @Test void resultPreservesNegationAndUnknown(){indexed();when(vectors.search(eq(List.of(OLD)),any(),eq(3))).thenReturn(List.of(new VectorIndex.Hit("key",.1)));when(catalog.locate(1L,chunk.chunkId(),1L,1L)).thenReturn(new DocChunkLocationDTO(chunk.chunkId(),1L,1L,1L,"chunk-v1",0,text.length(),text,text,true,true,"READY","PUBLISHED"));service.search(ID,List.of(1L),"支持双面打印吗",3,1);var result=ArgumentCaptor.forClass(Object.class);verify(store).done(eq(ID),result.capture());String json=RetrievalRules.json(result.getValue(),mapper);assertTrue(json.contains(text));assertTrue(json.contains("UNKNOWN"));assertTrue(json.contains("\"semantic\":true"));}
    @Test void corruptHitCannotBecomeEvidence(){indexed();when(vectors.search(anyList(),any(),anyInt())).thenReturn(List.of(new VectorIndex.Hit("invented",.1)));service.search(ID,List.of(1L),"双面",3,1);verify(store).done(eq(ID),argThat(x->"PARTIAL".equals(((Map<?,?>)x).get("status"))&&((List<?>)((Map<?,?>)x).get("items")).isEmpty()));verify(catalog,never()).locate(anyLong(),anyString(),anyLong(),anyLong());}
    @Test void hiddenDuringSearchFailsInsteadOfEmpty(){indexed();when(catalog.indexSnapshot(1L,1L,1L)).thenReturn(snapshot).thenThrow(new RuntimeException());service.search(ID,List.of(1L),"双面",3,1);verify(store).failed(ID,"RETRIEVAL_UNAVAILABLE");verify(store,never()).done(anyString(),any());}
    @Test void missingChunkInManifestRejected(){var bad=new IndexSnapshot(1L,"打印机",2,1L,List.of(new DocumentManifest(1L,1L,doc.contentHash(),"chunk-v1",2)),List.of(chunk));assertThrows(RuntimeException.class,()->RetrievalRules.snapshot(bad,1));}
    @Test void differentWholeDocumentHashInvalidatesLocation(){assertFalse(RetrievalRules.located(chunk,doc,new DocChunkLocationDTO(chunk.chunkId(),1L,1L,1L,"chunk-v1",0,text.length(),text,text+"修订",true,true,"READY","PUBLISHED")));}
}
