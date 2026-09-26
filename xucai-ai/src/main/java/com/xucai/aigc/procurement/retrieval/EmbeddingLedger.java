package com.xucai.aigc.procurement.retrieval;

public interface EmbeddingLedger {
    void reserve(String callId,String kind,int texts,int bytes,long reserve);
    void finish(String callId,Integer tokens,String error);
    default void reserveAssistant(String callId,int bytes,long reserve){throw new UnsupportedOperationException("Assistant ledger unavailable");}
}
