package com.xucai.aigc.procurement.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.common.exceptions.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Account ownership is checked on every query. Network/model work never runs inside these transactions. */
@Repository @Profile("xucai")
public class AssistantSessionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    public AssistantSessionStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper) {
        this.jdbc=jdbc; this.tx=tx; this.mapper=mapper;
    }
    public record Session(String id,String title,long revision,boolean archived,String updatedAt) {}
    public record Turn(String id,String sessionId,long ordinal,String text,String status,String reply,
                       JsonNode result,String errorCode,String createdAt,String finishedAt) {}
    public record Accepted(Turn turn,boolean created) {}
    private final RowMapper<Session> sessionRow=(r,n)->new Session(r.getString("id"),r.getString("title"),
        r.getLong("revision"),r.getBoolean("archived"),r.getTimestamp("updated_at").toLocalDateTime().toString());
    private RowMapper<Turn> turnRow(){return (r,n)->new Turn(r.getString("id"),r.getString("session_id"),
        r.getLong("ordinal"),r.getString("request_text"),r.getString("status"),r.getString("assistant_text"),
        read(r.getString("result_json")),r.getString("error_code"),r.getTimestamp("created_at").toLocalDateTime().toString(),
        r.getTimestamp("finished_at")==null?null:r.getTimestamp("finished_at").toLocalDateTime().toString());}
    private JsonNode read(String value){try{return value==null?null:mapper.readTree(value);}catch(Exception e){throw new IllegalStateException("Invalid stored assistant data",e);}}
    private String json(Object value){try{return value==null?null:mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid assistant result",e);}}
    static String title(String value){
        if(value==null||value.isBlank()||value.strip().length()>80)throw new BusinessException(400,"对话名称须为1到80个字符");
        return value.strip();
    }
    static String text(String value){
        if(value==null||value.isBlank()||value.length()>4000)throw new BusinessException(400,"请输入1到4000个字符");
        return value; // Exact request comparison: never normalize a retry into a different purchase instruction.
    }
    static void uuid(String id){
        if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new BusinessException(400,"对话或消息编号无效");
    }
    public List<Session> list(long user){return jdbc.query("SELECT * FROM assistant_session WHERE user_id=? AND archived=FALSE ORDER BY updated_at DESC,id DESC LIMIT 100",sessionRow,user);}
    public Session get(long user,String id){uuid(id);return find(user,id,false);}
    private Session find(long user,String id,boolean lock){return jdbc.query(
        "SELECT * FROM assistant_session WHERE id=? AND user_id=?"+(lock?" FOR UPDATE":""),sessionRow,id,user)
        .stream().findFirst().orElseThrow(()->new BusinessException(404,"对话不存在"));}
    public Session create(long user,String id,String name){
        uuid(id);String normalized=title(name);
        return tx.execute(s->{
            // Client UUID makes an unknown create response safely recoverable by GET, without a second conversation.
            jdbc.update("INSERT IGNORE INTO assistant_session(id,user_id,title) VALUES(?,?,?)",id,user,normalized);
            return find(user,id,true);
        });
    }
    public Session edit(long user,String id,long expected,String name,Boolean archived){
        uuid(id);if(expected<0)throw new BusinessException(400,"对话版本无效");
        if(name==null&&archived==null)throw new BusinessException(400,"没有需要修改的内容");
        String normalized=name==null?null:title(name);
        return tx.execute(s->{
            Session current=find(user,id,true);
            if(current.revision()!=expected)throw new BusinessException(409,"对话已更新，请刷新后再试");
            if(Boolean.TRUE.equals(archived)&&running(user,id))throw new BusinessException(409,"请先停止当前回复，再移除对话");
            jdbc.update("UPDATE assistant_session SET title=?,archived=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND user_id=?",
                normalized==null?current.title():normalized,archived==null?current.archived():archived,id,user);
            return find(user,id,false);
        });
    }
    private boolean running(long user,String id){return Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM assistant_turn WHERE session_id=? AND user_id=? AND status='RUNNING')",Boolean.class,id,user));}
    public List<Turn> turns(long user,String id){get(user,id);return jdbc.query(
        "SELECT * FROM assistant_turn WHERE session_id=? AND user_id=? ORDER BY ordinal ASC LIMIT 200",turnRow(),id,user);}
    public Turn turn(long user,String session,String id){uuid(session);uuid(id);return lookup(user,session,id)
        .orElseThrow(()->new BusinessException(404,"消息不存在"));}
    private Optional<Turn> lookup(long user,String session,String id){return jdbc.query(
        "SELECT * FROM assistant_turn WHERE id=? AND session_id=? AND user_id=?",turnRow(),id,session,user).stream().findFirst();}
    public Accepted accept(long user,String session,String id,String request){
        uuid(session);uuid(id);text(request);
        return tx.execute(s->{
            Session current=find(user,session,true);
            Optional<Turn> previous=lookup(user,session,id);
            if(previous.isPresent()){
                if(!previous.get().text().equals(request))throw new BusinessException(409,"同一消息编号不能更换内容");
                return new Accepted(previous.get(),false);
            }
            if(current.archived())throw new BusinessException(409,"对话已移除，请先恢复或新建对话");
            if(running(user,session))throw new BusinessException(409,"上一条消息还在处理中");
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM assistant_turn WHERE session_id=? AND user_id=?",Long.class,session,user);
            if(count==null||count>=200)throw new BusinessException(409,"这段对话较长，请新建对话继续");
            try{jdbc.update("INSERT INTO assistant_turn(id,session_id,user_id,ordinal,request_text,status) VALUES(?,?,?,?,?,'RUNNING')",id,session,user,count+1,request);}
            catch(org.springframework.dao.DuplicateKeyException e){throw new BusinessException(409,"消息编号已使用，请刷新核对");}
            jdbc.update("UPDATE assistant_session SET revision=revision+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND user_id=?",session,user);
            return new Accepted(turn(user,session,id),true);
        });
    }
    public boolean finish(long user,String session,String id,String status,String reply,Object result,String error){
        if(!Set.of("DONE","FAILED","CANCELED").contains(status))throw new IllegalArgumentException("Invalid terminal state");
        if("DONE".equals(status)&&(reply==null||reply.isBlank()))throw new IllegalArgumentException("Completed turn needs a reply");
        return jdbc.update("UPDATE assistant_turn SET status=?,assistant_text=?,result_json=?,error_code=?,finished_at=CURRENT_TIMESTAMP(3) WHERE id=? AND session_id=? AND user_id=? AND status='RUNNING'",
            status,reply,json(result),error,id,session,user)==1;
    }
    public Turn cancel(long user,String session,String id){
        turn(user,session,id);
        finish(user,session,id,"CANCELED",null,null,"USER_CANCELED");
        return turn(user,session,id);
    }
}
