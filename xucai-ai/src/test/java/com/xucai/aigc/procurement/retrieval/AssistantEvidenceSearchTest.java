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

class AssistantEvidenceSearchTest {
    final ObjectMapper mapper=new ObjectMapper();final CandidateCatalogClient catalog=mock(CandidateCatalogClient.class);
    final RetrievalStore store=mock(RetrievalStore.class);final VectorIndex vectors=mock(VectorIndex.class);final EmbeddingClient embeddings=mock(EmbeddingClient.class);
    final AssistantEvidenceSearch service=new AssistantEvidenceSearch(catalog,mapper,store,vectors,embeddings);
    final String call="00000000-0000-4000-8000-000000000001:101",text="不支持视频输出。";
    final Chunk chunk=new Chunk("doc:1:v1:c1",1L,1L,1L,"chunk-v1",0,text.length(),text,"官方规格","https://example.com/spec");
    final IndexSnapshot snapshot=new IndexSnapshot(1L,"设备",1,1L,List.of(new DocumentManifest(1L,1L,RetrievalRules.hash(text),"chunk-v1",1)),List.of(chunk));
    @BeforeEach void setup(){
        when(catalog.indexSnapshot(1L,7L,2L)).thenReturn(snapshot);
        when(catalog.locate(1L,chunk.chunkId(),7L,2L)).thenReturn(new DocChunkLocationDTO(chunk.chunkId(),1L,1L,1L,"chunk-v1",0,text.length(),text,text,true,true,"READY","PUBLISHED"));
        when(store.index(1)).thenReturn(Optional.of(new RetrievalStore.Index("generation",RetrievalRules.hash(RetrievalRules.json(snapshot,mapper)),snapshot)));
        when(vectors.complete(anyString(),anyList())).thenReturn(true);when(vectors.key("generation",chunk.chunkId())).thenReturn("key");
        when(embeddings.embedAssistant(call,"视频输出")).thenReturn(List.of(new float[1024]));
        when(vectors.search(eq(List.of("generation")),any(),eq(4))).thenReturn(List.of(new VectorIndex.Hit("key",0.1)));
    }
    @AfterEach void clearInterrupt(){Thread.interrupted();}
    @Test void semanticEvidenceKeepsNegationAndRevalidatesBuyerScope(){
        var result=service.search(call,1,7,"视频输出");assertTrue(result.semantic());assertEquals("HYBRID",result.mode());assertEquals(List.of(chunk),result.chunks());assertTrue(result.complete());
        verify(embeddings).embedAssistant(call,"视频输出");verify(catalog,times(2)).indexSnapshot(1L,7L,2L);verify(catalog,never()).indexSnapshot(anyLong(),anyLong(),eq(1L));
    }
    @Test void missingIndexUsesKeywordWithoutSpending(){when(store.index(1)).thenReturn(Optional.empty());
        var result=service.search(call,1,7,"视频输出");assertFalse(result.semantic());assertEquals("NOT_BUILT",result.fallbackReason());assertEquals(text,result.chunks().get(0).text());verifyNoInteractions(embeddings);
    }
    @Test void staleOrIncompleteIndexNeverCallsEmbedding(){
        when(vectors.complete(anyString(),anyList())).thenReturn(false);assertEquals("INCOMPLETE",service.search(call,1,7,"视频输出").fallbackReason());
        when(store.index(1)).thenReturn(Optional.of(new RetrievalStore.Index("generation","old",snapshot)));assertEquals("STALE_INDEX",service.search(call,1,7,"视频输出").fallbackReason());verifyNoInteractions(embeddings);
    }
    @Test void embeddingFailureFallsBackOnceWithoutRetry(){when(embeddings.embedAssistant(call,"视频输出")).thenThrow(new ModelCallException("EMBEDDING_AUTH","不可用"));
        var result=service.search(call,1,7,"视频输出");assertFalse(result.semantic());assertEquals("EMBEDDING_AUTH",result.fallbackReason());verify(embeddings,times(1)).embedAssistant(anyString(),anyString());
    }
    @Test void alienHitCannotEnterEvidence(){when(vectors.search(anyList(),any(),anyInt())).thenReturn(List.of(new VectorIndex.Hit("other-product",0.1)));
        var result=service.search(call,1,7,"视频输出");assertFalse(result.semantic());assertEquals(List.of(chunk),result.chunks());assertEquals("RETRIEVAL_HITS",result.fallbackReason());
    }
    @Test void changedOriginalFailsEvenWhenFallbackExists(){when(catalog.locate(anyLong(),anyString(),anyLong(),anyLong())).thenReturn(null);
        assertEquals("RETRIEVAL_STALE",assertThrows(ModelCallException.class,()->service.search(call,1,7,"视频输出")).code());
    }
    @Test void noKeywordMatchIsNotUnsupportedCapability(){when(store.index(1)).thenReturn(Optional.empty());
        var result=service.search(call,1,7,"蓝牙");assertTrue(result.chunks().isEmpty());assertTrue(result.notice().contains("不代表设备不支持"));assertFalse(result.complete());
    }
    @Test void canceledAfterEmbeddingDoesNotFallback(){when(embeddings.embedAssistant(call,"视频输出")).thenAnswer(i->{Thread.currentThread().interrupt();return List.of(new float[1024]);});
        assertEquals("MODEL_CANCELED",assertThrows(ModelCallException.class,()->service.search(call,1,7,"视频输出")).code());verify(vectors,never()).search(anyList(),any(),anyInt());
    }
    @Test void invalidQueryNeverFetchesEvidence(){for(String query:List.of("","x".repeat(301)))assertThrows(ModelCallException.class,()->service.search(call,1,7,query));verify(catalog,never()).indexSnapshot(anyLong(),anyLong(),anyLong());}
    Chunk item(int id,String text) {
        return new Chunk("doc:"+id+":v1:c1",1L,(long)id,1L,"chunk-v1",0,text.length(),text,"资料","https://example.com");
    }
    @Test void exactSkuOutsideVectorTopFourCanEnterFusedResults() {
        var v=List.of(item(1,"办公"),item(2,"接口"),item(3,"尺寸"),item(4,"分辨率"));
        var sku=item(5,"英国货号210-BMFF，中国货号210-BMMP。");
        var all=new ArrayList<Chunk>(v);all.add(sku);
        var fused=AssistantEvidenceSearch.fuse(v,AssistantEvidenceSearch.lexical(all,"210-BMFF"),4);
        assertEquals(4,fused.size());assertTrue(fused.contains(sku));assertFalse(v.contains(sku));
    }
    @Test void overlapBoostsRankButDuplicateChannelRowsDoNot() {
        var a=item(1,"a");var b=item(2,"b");var c=item(3,"c");
        assertEquals(b,AssistantEvidenceSearch.fuse(List.of(a,b,c),List.of(b),4).get(0));
        assertEquals(AssistantEvidenceSearch.fuse(List.of(a,b),List.of(c),4),
                AssistantEvidenceSearch.fuse(List.of(a,a,b),List.of(c,c),4));
    }
    @Test void lexicalOnlyHitStillMustPassLocateVerification() {
        var more=new ArrayList<Chunk>();for(int i=1;i<=5;i++)more.add(item(i,i==5?"210-BMFF":"other"+i));
        var manifests=more.stream().map(c->new DocumentManifest(c.docId(),1L,RetrievalRules.hash(c.text()),"chunk-v1",1)).toList();
        var current=new IndexSnapshot(1L,"设备",1,1L,manifests,more);
        when(catalog.indexSnapshot(1L,7L,2L)).thenReturn(current);
        when(store.index(1)).thenReturn(Optional.of(new RetrievalStore.Index("generation",RetrievalRules.hash(RetrievalRules.json(current,mapper)),current)));
        for(var c:more) {
            when(vectors.key("generation",c.chunkId())).thenReturn(c.chunkId());
            when(catalog.locate(1L,c.chunkId(),7L,2L)).thenReturn(new DocChunkLocationDTO(c.chunkId(),1L,c.docId(),1L,"chunk-v1",0,c.text().length(),c.text(),c.text(),true,true,"READY","PUBLISHED"));
        }
        when(embeddings.embedAssistant(call,"210-BMFF")).thenReturn(List.of(new float[1024]));
        when(vectors.search(anyList(),any(),eq(4))).thenReturn(more.subList(0,4).stream().map(c->new VectorIndex.Hit(c.chunkId(),0.1)).toList());
        when(catalog.locate(1L,"doc:5:v1:c1",7L,2L)).thenReturn(null);
        assertEquals("RETRIEVAL_STALE",assertThrows(ModelCallException.class,()->service.search(call,1,7,"210-BMFF")).code());
    }
}
