package com.xucai.aigc.procurement.retrieval;

import java.util.List;

/** 采购私有接口；刻意不实现全局 Spring AI EmbeddingModel。 */
public interface EmbeddingClient {
    String MODEL="text-embedding-v4";
    int DIMENSIONS=1024;
    List<float[]> embed(String callId,String kind,List<String> texts);
    default List<float[]> embedAssistant(String callId,String query){throw new UnsupportedOperationException("Assistant embedding unavailable");}
}
