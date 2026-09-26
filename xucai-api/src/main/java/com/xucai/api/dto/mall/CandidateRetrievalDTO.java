package com.xucai.api.dto.mall;

import java.util.List;

/** 生成时的检索审计快照；相似度不表示支持采购条件。旧候选此字段为空。 */
public record CandidateRetrievalDTO(String version,String model,int dimensions,String operationId,
        String queryHash,int perProductTopK,List<ProductScope> products) {
    public record ProductScope(long productId,String generation,String snapshotHash,List<Hit> hits,List<String> suppliedChunkIds) { }
    public record Hit(String chunkId,int rank,double distance) { }
}
