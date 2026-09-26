package com.xucai.trade.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.*;
import com.xucai.common.exceptions.BusinessException;
import com.xucai.trade.domain.dto.RequirementForm;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Statement;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RequirementService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private RowMapper<RequirementDTO> row() {
        return (r,n)->new RequirementDTO(r.getLong("id"),r.getLong("user_id"),r.getString("title"),
                r.getString("raw_text"),decode(r.getString("structured_json")),r.getLong("revision"),
                r.getString("status"),r.getTimestamp("created_at").toLocalDateTime().toString(),
                r.getTimestamp("updated_at").toLocalDateTime().toString());
    }
    private RequirementDetails decode(String value) {
        try{return mapper.readValue(value,RequirementDetails.class);}catch(JsonProcessingException e){throw new IllegalStateException("需求格式损坏",e);}
    }
    private String encode(RequirementDetails value) {
        try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalArgumentException("需求格式错误",e);}
    }
    public RequirementDTO get(long id,long user,boolean merchant) {
        List<RequirementDTO> rows=jdbc.query("SELECT * FROM procurement_requirement WHERE id=?"+(merchant?"":" AND user_id=?"),
                row(),merchant?new Object[]{id}:new Object[]{id,user});
        if(rows.isEmpty())throw new BusinessException(404,"需求不存在或不属于当前账户");
        return rows.get(0);
    }
    public Map<String,Object> list(long user,boolean merchant,int page,int size) {
        String where=merchant?"":" WHERE user_id=?";
        List<Object> args=new ArrayList<>();if(!merchant)args.add(user);
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM procurement_requirement"+where,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        return Map.of("total",count,"items",jdbc.query("SELECT * FROM procurement_requirement"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",row(),args.toArray()));
    }
    @Transactional
    public RequirementDTO create(long user,RequirementForm form) {
        if(form.expectedRevision()!=null)throw new BusinessException(400,"新需求不应携带旧版本");
        RequirementDetails details=RequirementRules.normalize(form.structured());
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(connection->{var p=connection.prepareStatement("INSERT INTO procurement_requirement(user_id,title,raw_text,structured_json) VALUES(?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            p.setLong(1,user);p.setString(2,form.title().trim());p.setString(3,form.rawText().trim());p.setString(4,encode(details));return p;},key);
        return get(Objects.requireNonNull(key.getKey()).longValue(),user,false);
    }
    @Transactional
    public RequirementDTO update(long id,long user,RequirementForm form) {
        RequirementDTO current=get(id,user,false);
        if(form.expectedRevision()==null)throw new BusinessException(400,"请携带需求版本");
        if(current.structured().items()!=null && form.structured().items()==null)
            throw new BusinessException(409,"当前需求已有分类数量，请刷新新版页面后编辑，避免丢失明细");
        RequirementDetails details=RequirementRules.normalize(form.structured());
        int changed=jdbc.update("UPDATE procurement_requirement SET title=?,raw_text=?,structured_json=?,revision=revision+1 WHERE id=? AND user_id=? AND revision=? AND status='OPEN'",
                form.title().trim(),form.rawText().trim(),encode(details),id,user,form.expectedRevision());
        if(changed!=1)throw new BusinessException(409,"需求已变化或已关闭，请读取最新版本后再保存");
        return get(id,user,false);
    }
    @Transactional
    public RequirementDTO status(long id,long user,long revision,String status) {
        get(id,user,false);
        if(!Set.of("OPEN","CLOSED").contains(status))throw new BusinessException(400,"需求状态不正确");
        int changed=jdbc.update("UPDATE procurement_requirement SET status=?,revision=revision+1 WHERE id=? AND user_id=? AND revision=? AND status IN ('OPEN','CLOSED') AND status<>?",status,id,user,revision,status);
        if(changed!=1)throw new BusinessException(409,"需求版本或状态已变化，请刷新");
        return get(id,user,false);
    }
}
