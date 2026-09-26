package com.xucai.aigc.procurement.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.CandidateCatalogDTO.*;
import com.xucai.api.dto.mall.DocChunkLocationDTO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.xucai.aigc.procurement.retrieval.DashScopeEmbeddingClient.error;

final class RetrievalRules {
    private RetrievalRules() { }
    static Set<String> legacySearchHashes(List<Long> products,String query,int topK,ObjectMapper mapper) {
        // 仅兼容首批已存三字段请求的Map.of遍历顺序；不改变值、数组次序或已有行。
        String[] keys={"products","query","topK"};Object[] values={products,query,topK};
        Set<String> hashes=new HashSet<>();
        for(int a=0;a<3;a++)for(int b=0;b<3;b++)if(a!=b) {
            int c=3-a-b;Map<String,Object> request=new LinkedHashMap<>();
            for(int i:new int[]{a,b,c})request.put(keys[i],values[i]);
            hashes.add(hash(json(request,mapper)));
        }
        return hashes;
    }
    static void snapshot(IndexSnapshot s,long product) {
        if(s==null || !Objects.equals(s.productId(),product) || s.categoryId()==null || s.categoryId()<1 || s.categoryId()>6 || s.specVersion()==null || s.specVersion()<1 || s.documents()==null || s.documents().isEmpty() || s.documents().size()>40 || s.chunks()==null || s.chunks().isEmpty() || s.chunks().size()>200)throw error("RETRIEVAL_MANIFEST");
        Map<Long,DocumentManifest> docs=new HashMap<>();int total=0;
        for(var d:s.documents()) {
            if(d.docId()==null || d.version()==null || d.version()<1 || d.contentHash()==null || !d.contentHash().matches("[0-9a-f]{64}") || d.ruleVersion()==null || d.ruleVersion().isBlank() || d.chunkCount()==null || d.chunkCount()<0 || d.chunkCount()>200 || docs.put(d.docId(),d)!=null)throw error("RETRIEVAL_MANIFEST");
            total+=d.chunkCount();
        }
        if(total!=s.chunks().size())throw error("RETRIEVAL_MANIFEST");
        Set<String> ids=new HashSet<>();Map<Long,Integer> counts=new HashMap<>();
        for(var c:s.chunks()) {
            var d=docs.get(c.docId());
            if(d==null || !Objects.equals(c.productId(),product) || !Objects.equals(c.version(),d.version()) || !Objects.equals(c.ruleVersion(),d.ruleVersion()) || c.chunkId()==null || !c.chunkId().matches("doc:"+c.docId()+":v"+c.version()+":c[1-9][0-9]*") || !ids.add(c.chunkId()) || c.charStart()==null || c.charEnd()==null || c.charStart()<0 || c.charEnd()<=c.charStart() || c.text()==null || c.text().isBlank() || c.text().length()!=c.charEnd()-c.charStart() || c.text().getBytes(StandardCharsets.UTF_8).length>4096)throw error("RETRIEVAL_MANIFEST");
            counts.merge(c.docId(),1,Integer::sum);
        }
        if(docs.values().stream().anyMatch(d->counts.getOrDefault(d.docId(),0).intValue()!=d.chunkCount()))throw error("RETRIEVAL_MANIFEST");
    }
    static boolean located(Chunk c,DocumentManifest d,DocChunkLocationDTO now) {
        return now!=null && Boolean.TRUE.equals(now.current()) && Boolean.TRUE.equals(now.located()) && "READY".equals(now.indexState()) && "PUBLISHED".equals(now.productStatus())
                && Objects.equals(c.productId(),now.productId()) && Objects.equals(c.docId(),now.docId()) && Objects.equals(c.version(),now.version()) && Objects.equals(c.ruleVersion(),now.ruleVersion())
                && Objects.equals(c.charStart(),now.charStart()) && Objects.equals(c.charEnd(),now.charEnd()) && Objects.equals(c.text(),now.text()) && now.content()!=null && hash(now.content()).equals(d.contentHash());
    }
    static String json(Object value,ObjectMapper mapper){try{return mapper.writeValueAsString(value);}catch(Exception e){throw error("RETRIEVAL_DATA");}}
    static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
