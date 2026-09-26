package com.xucai.aigc.procurement.retrieval;

import com.xucai.aigc.procurement.ModelCallException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component @Profile("xucai")
@ConditionalOnProperty(name="xucai.procurement.retrieval.enabled",havingValue="true")
public class JdbcEmbeddingLedger implements EmbeddingLedger {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public JdbcEmbeddingLedger(JdbcTemplate jdbc,TransactionTemplate tx){this.jdbc=jdbc;this.tx=tx;}
    @Override public void reserve(String id,String kind,int texts,int bytes,long reserve) {
        tx.executeWithoutResult(status->{
            // Retain historical accounting and lock order, without cumulative spending/call limits.
            int total=jdbc.update("UPDATE ai_model_budget SET reserved_micro_yuan=reserved_micro_yuan+? WHERE id='requirement-pilot-v1'",reserve);
            if(total!=1)throw ledgerUnavailable();
            int documents="document".equals(kind)?texts:0,queries="query".equals(kind)?texts:0;
            int updated=jdbc.update("UPDATE retrieval_budget SET used_calls=used_calls+1,used_documents=used_documents+?,used_queries=used_queries+?,reserved_micro_yuan=reserved_micro_yuan+? WHERE id='embedding-pilot-v1'",documents,queries,reserve);
            if(updated!=1)throw ledgerUnavailable();
            jdbc.update("INSERT INTO retrieval_model_call(id,kind,text_count,input_bytes,model,state,reserved_micro_yuan) VALUES(?,?,?,?,?,'RESERVED',?)",id,kind,texts,bytes,EmbeddingClient.MODEL,reserve);
        });
    }
    @Override public void finish(String id,Integer tokens,String error) {
        jdbc.update("UPDATE retrieval_model_call SET state=?,input_tokens=?,error_code=?,finished_at=CURRENT_TIMESTAMP(3) WHERE id=? AND state='RESERVED'",error==null?"COMPLETED":"FAILED",tokens,error,id);
    }
    @Override public void reserveAssistant(String id,int bytes,long reserve){
        // Assistant queries share the call ledger; all personal paths have no cumulative cap.
        // The UUID prefix is the persisted assistant turn; uniqueness prevents re-sending the same query.
        if(id==null||!id.matches("[0-9a-f-]{36}:10[1-3]")||bytes<1||bytes>4096)throw new IllegalArgumentException("Invalid assistant query");
        jdbc.update("INSERT INTO retrieval_model_call(id,kind,text_count,input_bytes,model,state,reserved_micro_yuan) VALUES(?,'query',1,?,?,'RESERVED',?)",id,bytes,EmbeddingClient.MODEL,reserve);
    }
    private static ModelCallException ledgerUnavailable(){return new ModelCallException("EMBEDDING_LEDGER_UNAVAILABLE","向量用量记录暂不可用，请稍后重试");}
}
