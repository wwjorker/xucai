package com.xucai.aigc.procurement.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.CandidateCatalogDTO.IndexSnapshot;
import com.xucai.common.exceptions.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Component @Profile("xucai")
@ConditionalOnProperty(name="xucai.procurement.retrieval.enabled",havingValue="true")
public class RetrievalStore {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper mapper;
    public RetrievalStore(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper mapper){this.jdbc=jdbc;this.tx=tx;this.mapper=mapper;}
    public record Index(String generation,String hash,IndexSnapshot snapshot) { }
    @jakarta.annotation.PostConstruct
    public void settleInterrupted() {
        // 本地单实例采购应用：重启后不续发收费请求，保留未知状态供原编号读取。
        jdbc.update("UPDATE retrieval_operation SET status='UNKNOWN',error_code='PROCESS_RESTART',finished_at=CURRENT_TIMESTAMP(3) WHERE status='RUNNING'");
        jdbc.update("UPDATE retrieval_model_call SET state='UNKNOWN',error_code='PROCESS_RESTART',finished_at=CURRENT_TIMESTAMP(3) WHERE state='RESERVED'");
    }
    boolean begin(String id,long user,String kind,String hash) {
        return begin(id,user,kind,hash,Set.of(hash));
    }
    boolean begin(String id,long user,String kind,String hash,Set<String> compatibleHashes) {
        try{jdbc.update("INSERT INTO retrieval_operation(id,created_by,kind,request_hash,status) VALUES(?,?,?,?,'RUNNING')",id,user,kind,hash);return true;}
        catch(org.springframework.dao.DuplicateKeyException e){var old=jdbc.queryForMap("SELECT created_by,kind,request_hash FROM retrieval_operation WHERE id=?",id);if(((Number)old.get("created_by")).longValue()!=user||!kind.equals(old.get("kind"))||!(hash.equals(old.get("request_hash"))||compatibleHashes.contains(old.get("request_hash"))))throw new BusinessException(409,"操作编号已用于另一请求，不得更换正文重试");return false;}
    }
    Map<String,Object> operation(String id,long user){
        var rows=jdbc.queryForList("SELECT id,status,result_json,error_code,created_at,finished_at FROM retrieval_operation WHERE id=? AND created_by=?",id,user);
        if(rows.isEmpty())throw new BusinessException(404,"检索操作不存在");var row=new LinkedHashMap<>(rows.get(0));
        Object raw=row.remove("result_json");if(raw!=null)try{row.put("result",mapper.readTree(raw.toString()));}catch(Exception e){throw DashScopeEmbeddingClient.error("RETRIEVAL_DATA");}
        row.put("historical",true);return row;
    }
    void done(String id,Object result){if(jdbc.update("UPDATE retrieval_operation SET status='DONE',result_json=?,finished_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='RUNNING'",RetrievalRules.json(result,mapper),id)!=1)throw DashScopeEmbeddingClient.error("RETRIEVAL_OPERATION_CHANGED");}
    void failed(String id,String code){jdbc.update("UPDATE retrieval_operation SET status='FAILED',error_code=?,finished_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='RUNNING'",code,id);}
    Optional<Index> index(long product){
        var rows=jdbc.queryForList("SELECT generation,snapshot_hash,model,dimensions,chunk_count,snapshot_json FROM retrieval_index WHERE product_id=?",product);
        if(rows.isEmpty())return Optional.empty();var row=rows.get(0);
        if(!EmbeddingClient.MODEL.equals(row.get("model"))||((Number)row.get("dimensions")).intValue()!=EmbeddingClient.DIMENSIONS)throw DashScopeEmbeddingClient.error("RETRIEVAL_INDEX_VERSION");
        try{var snapshot=mapper.readValue(row.get("snapshot_json").toString(),IndexSnapshot.class);RetrievalRules.snapshot(snapshot,product);
            if(snapshot.chunks().size()!=((Number)row.get("chunk_count")).intValue()||!RetrievalRules.hash(RetrievalRules.json(snapshot,mapper)).equals(row.get("snapshot_hash")))throw DashScopeEmbeddingClient.error("RETRIEVAL_MANIFEST");
            return Optional.of(new Index(row.get("generation").toString(),row.get("snapshot_hash").toString(),snapshot));
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw DashScopeEmbeddingClient.error("RETRIEVAL_DATA");}
    }
    void publish(String operation,IndexSnapshot snapshot,String hash,Object result){
        tx.executeWithoutResult(status->{
            var operationStatus=jdbc.queryForObject("SELECT status FROM retrieval_operation WHERE id=? FOR UPDATE",String.class,operation);
            if(!"RUNNING".equals(operationStatus))throw DashScopeEmbeddingClient.error("RETRIEVAL_OPERATION_CHANGED");
            jdbc.update("INSERT INTO retrieval_index(product_id,generation,snapshot_hash,model,dimensions,chunk_count,snapshot_json) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE generation=VALUES(generation),snapshot_hash=VALUES(snapshot_hash),model=VALUES(model),dimensions=VALUES(dimensions),chunk_count=VALUES(chunk_count),snapshot_json=VALUES(snapshot_json),built_at=CURRENT_TIMESTAMP(3)",snapshot.productId(),operation,hash,EmbeddingClient.MODEL,EmbeddingClient.DIMENSIONS,snapshot.chunks().size(),RetrievalRules.json(snapshot,mapper));
            done(operation,result);
        });
    }
}
