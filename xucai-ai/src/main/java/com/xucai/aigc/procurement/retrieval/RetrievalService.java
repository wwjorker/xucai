package com.xucai.aigc.procurement.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.aigc.procurement.ModelCallException;
import com.xucai.api.client.catalog.CandidateCatalogClient;
import com.xucai.api.dto.mall.CandidateCatalogDTO.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.xucai.aigc.procurement.retrieval.DashScopeEmbeddingClient.error;

@Service @Profile("xucai")
@ConditionalOnProperty(name="xucai.procurement.retrieval.enabled",havingValue="true")
public class RetrievalService {
    private final CandidateCatalogClient catalog;private final EmbeddingClient embeddings;private final VectorIndex vectors;private final RetrievalStore store;private final ObjectMapper mapper;
    public RetrievalService(CandidateCatalogClient catalog,EmbeddingClient embeddings,VectorIndex vectors,RetrievalStore store,ObjectMapper mapper){this.catalog=catalog;this.embeddings=embeddings;this.vectors=vectors;this.store=store;this.mapper=mapper;}
    private IndexSnapshot snapshot(long product,long user){var s=catalog.indexSnapshot(product,user,1L);RetrievalRules.snapshot(s,product);return s;}
    private String hash(Object value){return RetrievalRules.hash(RetrievalRules.json(value,mapper));}
    public Map<String,Object> operation(String id,long user){return store.operation(id,user);}
    public Map<String,Object> build(String id,long product,long user) {
        if(!store.begin(id,user,"BUILD",hash(Map.of("productId",product))))return store.operation(id,user);
        try {
            var before=snapshot(product,user);String fingerprint=hash(before);
            var old=store.index(product);
            vectors.ensureSchema();
            if(old.isPresent()&&old.get().hash().equals(fingerprint)&&vectors.complete(old.get().generation(),before.chunks().stream().map(Chunk::chunkId).toList())) {
                store.done(id,Map.of("status","READY","reused",true,"productId",product,"generation",old.get().generation(),"chunkCount",before.chunks().size()));
                return store.operation(id,user);
            }
            for(int offset=0,batch=0;offset<before.chunks().size();offset+=10,batch++) {
                var parts=before.chunks().subList(offset,Math.min(offset+10,before.chunks().size()));
                var result=embeddings.embed(id+":"+batch,"document",parts.stream().map(Chunk::text).toList());
                if(result.size()!=parts.size())throw error("EMBEDDING_OUTPUT");
                for(int i=0;i<parts.size();i++)vectors.put(id,parts.get(i).chunkId(),result.get(i));
            }
            if(!vectors.complete(id,before.chunks().stream().map(Chunk::chunkId).toList()))throw error("RETRIEVAL_INCOMPLETE");
            if(!hash(snapshot(product,user)).equals(fingerprint))throw error("RETRIEVAL_STALE");
            // 新代次全部落地并核对后才切换指针；旧代次留作派生缓存，不删除正文或旧方案。
            store.publish(id,before,fingerprint,Map.of("status","READY","reused",false,"productId",product,"generation",id,"chunkCount",before.chunks().size()));
        }catch(Exception e){fail(id,e);}
        return store.operation(id,user);
    }
    public Map<String,Object> search(String id,List<Long> products,String query,int topK,long user) {
        if(products==null||products.isEmpty()||products.size()>20||products.stream().anyMatch(p->p==null||p<1)||new HashSet<>(products).size()!=products.size()||query==null||query.isBlank()||query.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096||topK<1||topK>20)throw error("RETRIEVAL_INPUT");
        var request=new TreeMap<String,Object>(Map.of("products",products,"query",query,"topK",topK));
        if(!store.begin(id,user,"SEARCH",hash(request),RetrievalRules.legacySearchHashes(products,query,topK,mapper)))return store.operation(id,user);
        try {
            List<String> generations=new ArrayList<>();Map<String,Chunk> sources=new HashMap<>();Map<Long,DocumentManifest> documents=new HashMap<>();Map<Long,String> fingerprints=new HashMap<>();
            for(long product:products) {
                var index=store.index(product);
                if(index.isEmpty()){store.done(id,notReady("NOT_BUILT",product));return store.operation(id,user);}
                var current=snapshot(product,user);String fingerprint=hash(current);
                if(!index.get().hash().equals(fingerprint)){store.done(id,notReady("STALE",product));return store.operation(id,user);}
                vectors.ensureSchema();
                if(!vectors.complete(index.get().generation(),current.chunks().stream().map(Chunk::chunkId).toList())){store.done(id,notReady("INCOMPLETE",product));return store.operation(id,user);}
                fingerprints.put(product,fingerprint);generations.add(index.get().generation());
                current.documents().forEach(d->documents.put(d.docId(),d));
                for(var c:current.chunks())sources.put(vectors.key(index.get().generation(),c.chunkId()),c);
            }
            // 无完整当前索引时不调用query embedding；文本只发模型，商品/身份/订单等不发送。
            var queryVector=embeddings.embed(id+":0","query",List.of(query));
            var hits=vectors.search(generations,queryVector.get(0),topK);
            List<Map<String,Object>> items=new ArrayList<>();int dropped=0;Set<String> seen=new HashSet<>();
            for(var hit:hits) {
                var chunk=sources.get(hit.key());
                if(chunk==null || !seen.add(hit.key()) || !Double.isFinite(hit.distance()) || hit.distance()<0 || hit.distance()>2){dropped++;continue;}
                var located=catalog.locate(chunk.productId(),chunk.chunkId(),user,1L);
                if(!RetrievalRules.located(chunk,documents.get(chunk.docId()),located)){dropped++;continue;}
                items.add(Map.of("chunk",chunk,"distance",hit.distance(),"verdict","UNKNOWN"));
            }
            // 检索过程遇更正/隐藏时整次失败，不能把旧向量的空命中说成无相关资料。
            for(var entry:fingerprints.entrySet())if(!hash(snapshot(entry.getKey(),user)).equals(entry.getValue()))throw error("RETRIEVAL_STALE");
            store.done(id,Map.of("status",dropped>0?"PARTIAL":"SEARCHED","semantic",true,"model",EmbeddingClient.MODEL,"dimensions",EmbeddingClient.DIMENSIONS,
                    "items",items,"dropped",dropped,"productIds",products,"notice","仅在指定商品当前资料内进行向量相似度排序；相似不等于支持采购结论，引用仍需人工核实。这是检索时快照。"));
        }catch(Exception e){fail(id,e);}
        return store.operation(id,user);
    }
    private static Map<String,Object> notReady(String state,long product){return Map.of("status",state,"semantic",false,"productId",product,"items",List.of(),"notice","向量索引尚未完整就绪，未调用查询模型；不能当作没有相关内容。");}
    private void fail(String id,Exception e){String code=e instanceof ModelCallException m?m.code():e instanceof feign.FeignException?"RETRIEVAL_CATALOG":"RETRIEVAL_UNAVAILABLE";try{store.failed(id,code);}catch(RuntimeException ignored){/* 保留RUNNING未知记录；原UUID不得再次启动调用。*/}}
}
