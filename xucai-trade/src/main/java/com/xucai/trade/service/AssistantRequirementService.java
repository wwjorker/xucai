package com.xucai.trade.service;

import com.xucai.api.client.ai.AssistantDraftClient;
import com.xucai.api.dto.mall.RequirementDTO;
import com.xucai.common.exceptions.BusinessException;
import com.xucai.trade.domain.dto.RequirementForm;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service @RequiredArgsConstructor
public class AssistantRequirementService {
    private final JdbcTemplate jdbc;
    private final RequirementService requirements;
    private final AssistantDraftClient drafts;
    private final TransactionTemplate tx;
    public record Link(Long requirementId) {}
    static void identifiers(String session,String turn){
        try{if(!UUID.fromString(session).toString().equals(session)||!UUID.fromString(turn).toString().equals(turn))throw new IllegalArgumentException();}
        catch(RuntimeException e){throw new BusinessException(400,"草稿编号格式不正确");}
    }
    public Link find(long user,String session,String turn){
        identifiers(session,turn);
        var rows=jdbc.query("SELECT requirement_id FROM requirement_assistant_source WHERE user_id=? AND session_id=? AND turn_id=?",
            (r,n)->r.getObject("requirement_id",Long.class),user,session,turn);
        return new Link(rows.isEmpty()?null:rows.get(0));
    }
    public RequirementDTO create(long user,String session,String turn,RequirementForm form){
        if(form.expectedRevision()!=null)throw new BusinessException(400,"草稿保存不能携带旧需求版本");
        Long existing=find(user,session,turn).requirementId();
        if(existing!=null)return requirements.get(existing,user,false);
        // The AI ownership check is a read, outside the database transaction. No model request is made.
        var source=drafts.turn(session,turn,user,2L);
        if(source==null||!"DONE".equals(source.path("status").asText())||!source.path("result").path("requirementDraft").isObject())
            throw new BusinessException(409,"这条对话没有已完成的采购草稿");
        return tx.execute(status->{
            jdbc.update("INSERT INTO requirement_assistant_source(user_id,session_id,turn_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE user_id=user_id",user,session,turn);
            Long linked=jdbc.queryForObject("SELECT requirement_id FROM requirement_assistant_source WHERE user_id=? AND session_id=? AND turn_id=? FOR UPDATE",Long.class,user,session,turn);
            if(linked!=null)return requirements.get(linked,user,false);
            var saved=requirements.create(user,form);
            jdbc.update("UPDATE requirement_assistant_source SET requirement_id=? WHERE user_id=? AND session_id=? AND turn_id=?",saved.id(),user,session,turn);
            return saved;
        });
    }
}
