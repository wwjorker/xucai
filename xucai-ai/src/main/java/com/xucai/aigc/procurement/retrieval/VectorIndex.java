package com.xucai.aigc.procurement.retrieval;

import java.util.List;

interface VectorIndex {
    record Hit(String key,double distance) { }
    void ensureSchema();
    void put(String generation,String chunkId,float[] vector);
    long count(String generation);
    boolean complete(String generation,List<String> chunkIds);
    List<Hit> search(List<String> generations,float[] vector,int count);
    String key(String generation,String chunkId);
}
