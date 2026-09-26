package com.xucai.aigc.procurement.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.aigc.procurement.*;
import com.xucai.api.client.catalog.CandidateCatalogClient;
import com.xucai.api.dto.mall.*;
import com.xucai.api.dto.mall.CandidateCatalogDTO.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 客户候选专用只读检索。不会借商家身份或自动建立收费文档索引。 */
@Component @Profile("xucai")
@ConditionalOnProperty(name="xucai.procurement.retrieval.enabled",havingValue="true")
public class CandidateVectorRetriever implements CandidateEvidenceRetriever {
    static final int TOP_K=3;
    private final CandidateCatalogClient catalog;
    private final EmbeddingClient embeddings;
    private final VectorIndex vectors;
    private final RetrievalStore store;
    private final ObjectMapper mapper;
    public CandidateVectorRetriever(CandidateCatalogClient catalog,EmbeddingClient embeddings,VectorIndex vectors,RetrievalStore store,ObjectMapper mapper) {
        this.catalog=catalog;this.embeddings=embeddings;this.vectors=vectors;this.store=store;this.mapper=mapper;
    }
    static void active(){if(Thread.currentThread().isInterrupted())throw error("MODEL_CANCELED","候选检索已取消，未继续生成");}
    static ModelCallException error(String code,String message){return new ModelCallException(code,message);}
    private String hash(Object value){return RetrievalRules.hash(RetrievalRules.json(value,mapper));}
    private IndexSnapshot snapshot(Product product,long user) {
        active();var current=catalog.indexSnapshot(product.id(),user,2L);
        RetrievalRules.snapshot(current,product.id());
        if(!Objects.equals(current.specVersion(),product.specVersion()) || !Objects.equals(current.categoryId(),product.categoryId()))
            throw error("CANDIDATE_STALE","检索期间商品规格已变化，请重新生成");
        return current;
    }
    @Override public Evidence retrieve(String runId,RequirementDTO requirement,List<Product> products) {
        active();
        if(products==null || products.isEmpty() || products.size()>36 || products.stream().map(Product::id).distinct().count()!=products.size())
            throw error("CANDIDATE_RETRIEVAL_INPUT","候选检索范围不正确");
        var details=requirement.structured();
        // 仅公开采购条件，不外发账户、订单、原始输入中的其他信息。
        String query=RetrievalRules.json(Map.of("usage",details.usage(),"mustHave",details.mustHave(),"optional",details.optional()),mapper);
        if(query.getBytes(StandardCharsets.UTF_8).length>4096)throw error("CANDIDATE_QUERY_TOO_LONG","采购用途与条件超过检索上限，请缩短条件或由商家人工编制");
        try {
            Map<Long,RetrievalStore.Index> indexes=new LinkedHashMap<>();
            Map<Long,IndexSnapshot> snapshots=new LinkedHashMap<>();
            vectors.ensureSchema();
            for(var product:products) {
                active();var index=store.index(product.id()).orElseThrow(()->error("CANDIDATE_INDEX_NOT_READY","候选商品的向量索引尚未建立，请由商家完成资料索引后再试"));
                var current=snapshot(product,requirement.userId());
                if(!index.hash().equals(hash(current)) || !vectors.complete(index.generation(),current.chunks().stream().map(Chunk::chunkId).toList()))
                    throw error("CANDIDATE_INDEX_NOT_READY","候选商品资料索引已过期或不完整，请由商家重建后再试");
                indexes.put(product.id(),index);snapshots.put(product.id(),current);
            }
            active();
            // 整个运行仅一次query调用；唯一调用编号由持久账本防止重复发送。
            var embedded=embeddings.embed(runId+":0","query",List.of(query));
            active();
            if(embedded.size()!=1)throw error("CANDIDATE_RETRIEVAL_OUTPUT","检索向量不完整，未生成候选");
            Map<Long,List<Chunk>> evidence=new LinkedHashMap<>();List<CandidateRetrievalDTO.ProductScope> scopes=new ArrayList<>();boolean partial=false;
            for(var product:products) {
                active();var index=indexes.get(product.id());var current=snapshots.get(product.id());
                Map<String,Chunk> sources=new HashMap<>();Map<Long,DocumentManifest> docs=new HashMap<>();
                current.chunks().forEach(c->sources.put(vectors.key(index.generation(),c.chunkId()),c));
                current.documents().forEach(d->docs.put(d.docId(),d));
                // 每款商品独立取topK，防止其他分类的高分片段挤掉这款的全部资料。
                var hits=vectors.search(List.of(index.generation()),embedded.get(0),TOP_K);
                if(hits.size()!=Math.min(TOP_K,current.chunks().size()))throw error("CANDIDATE_RETRIEVAL_INCOMPLETE","检索结果不完整，未生成候选");
                List<Chunk> chunks=new ArrayList<>();List<CandidateRetrievalDTO.Hit> ranked=new ArrayList<>();Set<String> seen=new HashSet<>();
                for(var hit:hits) {
                    active();var chunk=sources.get(hit.key());
                    if(chunk==null || !seen.add(hit.key()) || !Double.isFinite(hit.distance()) || hit.distance()<0 || hit.distance()>2)
                        throw error("CANDIDATE_RETRIEVAL_INCOMPLETE","检索片段完整性复核失败，未生成候选");
                    var located=catalog.locate(product.id(),chunk.chunkId(),requirement.userId(),2L);
                    if(!RetrievalRules.located(chunk,docs.get(chunk.docId()),located))throw error("CANDIDATE_STALE","检索引用已变化，未生成候选");
                    ranked.add(new CandidateRetrievalDTO.Hit(chunk.chunkId(),ranked.size()+1,hit.distance()));
                    if(chunk.text().length()<=1000)chunks.add(chunk);else partial=true;
                }
                partial|=current.chunks().size()>chunks.size();evidence.put(product.id(),List.copyOf(chunks));
                scopes.add(new CandidateRetrievalDTO.ProductScope(product.id(),index.generation(),index.hash(),List.copyOf(ranked),List.of()));
            }
            for(var product:products)if(!indexes.get(product.id()).hash().equals(hash(snapshot(product,requirement.userId()))))
                throw error("CANDIDATE_STALE","检索期间资料版本已变化，未生成候选");
            active();
            return new Evidence(Map.copyOf(evidence),new CandidateRetrievalDTO("candidate-vector-v1",EmbeddingClient.MODEL,EmbeddingClient.DIMENSIONS,runId,RetrievalRules.hash(query),TOP_K,List.copyOf(scopes)),partial);
        }catch(feign.FeignException e){throw error("CANDIDATE_CATALOG","候选资料服务暂不可用，未生成候选");}
    }
}
