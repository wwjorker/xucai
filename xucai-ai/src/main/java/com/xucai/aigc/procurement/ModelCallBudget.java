package com.xucai.aigc.procurement;

import com.xucai.api.dto.mall.RequirementDTO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name="xucai.procurement.model.enabled",havingValue="true")
public class ModelCallBudget {
    // 请求体最多16384 UTF-8字节（含提示），输出最多2048 token；不扣除免费额度或缓存优惠。
    // 两个允许的固定模型共用此保守上界；Flash <=32K为输入0.2/输出0.8元每百万token。
    // 不因模型更便宜而退还旧预留；新增型号先核对价格再加入允许列表。
    static final long RESERVE=20000;
    static final String BUDGET="requirement-pilot-v1";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public ModelCallBudget(JdbcTemplate jdbc,TransactionTemplate tx){this.jdbc=jdbc;this.tx=tx;}
    public boolean available(){
        // Historical aggregate is retained for continuity, never used as a spending gate.
        try { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)=1 FROM ai_model_budget WHERE id=?",Boolean.class,BUDGET)); }
        catch(org.springframework.dao.DataAccessException e){return false;}
    }
    public void reserve(String runId,RequirementDTO input,String model){
        tx.executeWithoutResult(status->{
            int updated=jdbc.update("UPDATE ai_model_budget SET used_calls=used_calls+1,reserved_micro_yuan=reserved_micro_yuan+? WHERE id=?",RESERVE,BUDGET);
            if(updated!=1)throw new ModelCallException("MODEL_LEDGER_UNAVAILABLE","AI用量记录暂不可用，请稍后重试");
            jdbc.update("INSERT INTO ai_model_call(run_id,requirement_id,requirement_revision,model,state,reserved_micro_yuan) VALUES(?,?,?,?,'RESERVED',?)",runId,input.id(),input.revision(),model,RESERVE);
        });
    }
    public void finish(String runId,String state,Integer in,Integer out,String code){
        // 预留额不返还。无响应/取消/进程中断仍计一次，重启不会恢复次数。
        jdbc.update("UPDATE ai_model_call SET state=?,input_tokens=?,output_tokens=?,error_code=?,finished_at=CURRENT_TIMESTAMP(3) WHERE run_id=? AND state='RESERVED'",state,in,out,code,runId);
    }
}
