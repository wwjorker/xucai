package com.xucai.aigc.procurement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.RequirementDTO;
import com.xucai.common.exceptions.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository @RequiredArgsConstructor
public class JdbcRunStore implements RunStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private String json(Object value){try{return value==null?null:mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private <T>T read(String value,Class<T> type){try{return value==null?null:mapper.readValue(value,type);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private RowMapper<RunDTO> row(){return (r,n)->new RunDTO(r.getString("id"),r.getLong("user_id"),r.getLong("requirement_id"),
            r.getLong("requirement_revision"),r.getString("status"),r.getString("model"),r.getString("prompt_version"),
            read(r.getString("input_json"),RequirementDTO.class),read(r.getString("result_json"),AnalysisResult.class),
            r.getString("error_code"),r.getString("error_message"),r.getTimestamp("started_at").toLocalDateTime().toString(),
            r.getTimestamp("finished_at")==null?null:r.getTimestamp("finished_at").toLocalDateTime().toString());}
    @Override public void insert(String id,RequirementDTO input,String model){
        insert(id,input,model,"disabled".equals(model)?"requirement-v1":"requirement-extract-v8-explicit-limits");
    }
    @Override public void insert(String id,RequirementDTO input,String model,String version){
        try{jdbc.update("INSERT INTO ai_run(id,user_id,requirement_id,requirement_revision,input_json,model,prompt_version) VALUES(?,?,?,?,?,?,?)",id,input.userId(),input.id(),input.revision(),json(input),model,version);}
        catch(DuplicateKeyException e){throw new BusinessException(409,"已有需求整理正在运行，请等待或取消后重试");}
    }
    @Override public RunDTO get(String id,long userId){return jdbc.query("SELECT * FROM ai_run WHERE id=? AND user_id=?",row(),id,userId).stream().findFirst().orElseThrow(()->new BusinessException(404,"运行记录不存在或不属于当前账户"));}
    @Override public List<RunDTO> list(long requirementId,long userId){return jdbc.query("SELECT * FROM ai_run WHERE requirement_id=? AND user_id=? ORDER BY started_at DESC,id DESC LIMIT 20",row(),requirementId,userId);}
    public List<RunDTO> merchantCandidates(long requirementId){return jdbc.query("SELECT * FROM ai_run WHERE requirement_id=? AND status='DONE' AND prompt_version LIKE 'candidate-reference-%' ORDER BY started_at DESC,id DESC LIMIT 20",row(),requirementId);}
    public RunDTO merchantCandidate(long requirementId,String id){return jdbc.query("SELECT * FROM ai_run WHERE requirement_id=? AND id=? AND status='DONE' AND prompt_version LIKE 'candidate-reference-%'",row(),requirementId,id).stream().findFirst().orElseThrow(()->new BusinessException(404,"此需求没有可采用的已完成候选"));}
    @Override public boolean finish(String id,String status,AnalysisResult result,String code,String message){
        if(!List.of("DONE","FAILED","CANCELED").contains(status))throw new IllegalArgumentException("invalid terminal state");
        if(("DONE".equals(status))!=(result!=null))throw new IllegalArgumentException("result must match DONE");
        // 结果与终态在同一条条件UPDATE中写入；取消、超时与迟到结果只能有一个获胜。
        return jdbc.update("UPDATE ai_run SET status=?,result_json=?,error_code=?,error_message=?,finished_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='RUNNING'",status,json(result),code,message,id)==1;
    }
    @Override public int recover(){return jdbc.update("UPDATE ai_run SET status='FAILED',error_code='PROCESS_RESTARTED',error_message='服务已重新启动，请重新整理需求',finished_at=CURRENT_TIMESTAMP(3) WHERE status='RUNNING'");}
}
