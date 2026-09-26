package com.xucai.trade.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.client.catalog.CatalogClient;
import com.xucai.api.dto.mall.*;
import com.xucai.trade.domain.dto.PlanDraftForm;
import com.xucai.common.exceptions.BusinessException;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service @RequiredArgsConstructor
public class PlanDraftService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RequirementService requirements;
    private final CatalogClient catalog;
    private final TransactionTemplate transactions;
    private String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("方案序列化失败",e);}
    }
    private JsonNode decode(String value) {
        try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("方案快照损坏",e);}
    }
    public Map<String,Object> detail(String id,long merchant) {
        var rows=jdbc.query("SELECT p.*,r.revision AS current_revision,r.status AS requirement_status FROM procurement_plan p JOIN procurement_requirement r ON r.id=p.requirement_id WHERE p.id=?",(r,n)->{
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("id",r.getString("id"));data.put("requirementId",r.getLong("requirement_id"));
            data.put("requirementRevision",r.getLong("requirement_revision"));data.put("planNo",r.getInt("plan_no"));
            data.put("status",r.getString("status"));data.put("source",r.getString("source"));data.put("summary",r.getString("summary"));
            data.put("effectiveStatus","ORDERED".equals(r.getString("status")) ? "ORDERED" : r.getLong("current_revision")==r.getLong("requirement_revision") && "OPEN".equals(r.getString("requirement_status"))?r.getString("status"):"STALE");
            data.put("totalAmount",r.getLong("total_amount"));data.put("snapshot",decode(r.getString("snapshot_json")));
            data.put("createdBy",r.getLong("created_by"));data.put("createdAt",r.getTimestamp("created_at").toLocalDateTime().toString());
            return data;
        },id);
        if(rows.isEmpty())throw new BusinessException(404,"方案不存在");
        return rows.get(0);
    }
    public Map<String,Object> list(long requirementId,long merchant,int page,int size) {
        requirements.get(requirementId,merchant,true);
        var items=jdbc.queryForList("SELECT p.id,p.plan_no AS planNo,p.requirement_revision AS requirementRevision,p.source,p.status,p.summary,p.total_amount AS totalAmount,p.created_at AS createdAt,CASE WHEN p.status='ORDERED' THEN 'ORDERED' WHEN p.requirement_revision=r.revision AND r.status='OPEN' THEN p.status ELSE 'STALE' END AS effectiveStatus FROM procurement_plan p JOIN procurement_requirement r ON r.id=p.requirement_id WHERE p.requirement_id=? ORDER BY p.plan_no DESC LIMIT ? OFFSET ?",requirementId,size,(page-1)*size);
        return Map.of("items",items,"total",jdbc.queryForObject("SELECT COUNT(*) FROM procurement_plan WHERE requirement_id=?",Long.class,requirementId));
    }
    private Map<String,Object> replay(PlanDraftForm form,String hash,long requirementId,long merchant) {
        var old=jdbc.queryForList("SELECT request_hash,requirement_id,created_by FROM procurement_plan WHERE id=?",form.requestId());
        if(old.isEmpty())return null;
        var row=old.get(0);
        if(!hash.equals(row.get("request_hash")) || ((Number)row.get("requirement_id")).longValue()!=requirementId || ((Number)row.get("created_by")).longValue()!=merchant)
            throw new BusinessException(409,"本次保存标识已用于其他内容，请重新载入草稿后保存新版本");
        return detail(form.requestId(),merchant);
    }
    public Map<String,Object> create(long requirementId,long merchant,PlanDraftForm form) {
        return create(requirementId,merchant,form,null);
    }
    public Map<String,Object> candidateReplay(String runId,long requirementId,long merchant) {
        var rows=jdbc.queryForList("SELECT plan_id,requirement_id FROM candidate_plan_import WHERE run_id=?",runId);
        if(rows.isEmpty())return null;
        var row=rows.get(0);
        if(((Number)row.get("requirement_id")).longValue()!=requirementId)throw new BusinessException(409,"此候选已用于另一采购需求");
        return detail((String)row.get("plan_id"),merchant);
    }
    public Map<String,Object> createCandidate(long requirementId,long merchant,PlanDraftForm form,CandidateRunDTO origin) {
        return create(requirementId,merchant,form,origin);
    }
    private Map<String,Object> replayFor(PlanDraftForm form,String hash,long requirementId,long merchant,CandidateRunDTO origin) {
        if(origin!=null){var old=candidateReplay(origin.runId(),requirementId,merchant);if(old!=null)return old;
            if(!jdbc.queryForList("SELECT id FROM procurement_plan WHERE id=?",form.requestId()).isEmpty())throw new BusinessException(409,"候选草稿标识已被其他保存使用");
            return null;}
        return replay(form,hash,requirementId,merchant);
    }
    private Map<String,Object> create(long requirementId,long merchant,PlanDraftForm form,CandidateRunDTO origin) {
        String hash;
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(form).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        var existing=replayFor(form,hash,requirementId,merchant,origin);if(existing!=null)return existing;
        var req=requirements.get(requirementId,merchant,true);
        if(!"OPEN".equals(req.status()) || req.revision()!=form.expectedRequirementRevision().longValue())throw new BusinessException(409,"需求已变化或关闭，请读取最新需求后编制草稿");
        var ids=form.items().stream().map(PlanDraftForm.Item::productId).toList();
        if(new HashSet<>(ids).size()!=ids.size())throw new BusinessException(400,"同一商品请合并为一行数量");
        if(form.items().stream().mapToInt(PlanDraftForm.Item::quantity).sum()>10000)throw new BusinessException(400,"设备总件数不能超过10000");
        // 外部读取全部在本地写事务前；不持需求行锁等待Feign。
        Map<Long,CatalogSnapshotDTO> products=new HashMap<>();
        for(long id:ids)try{products.put(id,catalog.snapshot(id));}catch(FeignException e){throw new BusinessException(e.status()==404?404:503,"商品资料暂不可用，草稿未保存");}
        // 新引用要核实真实片段，不能只认可客户端提交的编号/规则。外部调用仍不持数据库写锁。
        for(var line:form.items())for(var evidence:line.evidence())if(evidence.chunk()!=null) {
            try {
                PlanDraftRules.verifyChunkSource(evidence,products.get(line.productId()),catalog.locateChunk(evidence.chunk().chunkId()));
            } catch(FeignException e) {
                throw new BusinessException(e.status()==400?400:e.status()==404 || e.status()==409?409:503,
                        "片段暂不可用或已变化，草稿未保存，请重新读取商品后选取片段");
            }
        }
        try { return transactions.execute(tx->{
            jdbc.queryForList("SELECT id FROM procurement_requirement WHERE id=? FOR UPDATE",requirementId);
            var repeated=replayFor(form,hash,requirementId,merchant,origin);if(repeated!=null)return repeated;
            // 锁定后重新读取，不能把事务外的需求版本当作原子校验。
            var current=requirements.get(requirementId,merchant,true);
            if(!"OPEN".equals(current.status()) || current.revision()!=form.expectedRequirementRevision().longValue())throw new BusinessException(409,"需求已变化或关闭，请读取最新需求后编制草稿");
            List<PlanDraftRules.Item> items=new ArrayList<>();Map<Long,SaleItemDTO> saleMap=new HashMap<>();
            // 按商品升序锁售卖行，保存的价格/版本与本地事务一致；不扣库存。
            for(var line:form.items().stream().sorted(Comparator.comparing(PlanDraftForm.Item::productId)).toList()) {
                var rows=jdbc.query("SELECT * FROM sale_item WHERE product_id=? FOR UPDATE",
                        (r,n)->new SaleItemDTO(r.getLong("product_id"),r.getLong("price"),r.getInt("stock"),r.getBoolean("on_sale"),r.getLong("sale_version")),line.productId());
                var sale=rows.isEmpty()?null:rows.get(0);saleMap.put(line.productId(),sale);
                if(origin!=null){var source=origin.candidate().items().stream().filter(i->i.productId()==line.productId()).findFirst().orElseThrow();
                    if(sale==null || sale.price()!=source.unitPrice())throw new BusinessException(409,"候选价格已变化，请重新核实商品");}
                items.add(PlanDraftRules.item(line,products.get(line.productId()),sale));
            }
            int number=jdbc.queryForObject("SELECT COALESCE(MAX(plan_no),0)+1 FROM procurement_plan WHERE requirement_id=?",Integer.class,requirementId);
            var warnings=new ArrayList<>(PlanDraftRules.warnings(current,items,saleMap));
            if(origin!=null)warnings.add("由商家采用已完成AI候选；偏好、必须条件及引用支持关系尚未核实，合并摘录保留连续原文。"+(origin.candidate().partialPool()?"原候选范围有截取。":""));
            var snapshot=new PlanDraftRules.Snapshot(current,List.copyOf(items),List.copyOf(warnings),origin);
            jdbc.update("INSERT INTO procurement_plan(id,request_hash,requirement_id,requirement_revision,plan_no,summary,total_amount,snapshot_json,created_by,source) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    form.requestId(),hash,requirementId,current.revision(),number,form.summary().trim(),PlanDraftRules.total(items),encode(snapshot),merchant,origin==null?"MERCHANT":"AI");
            if(origin!=null)jdbc.update("INSERT INTO candidate_plan_import(run_id,plan_id,requirement_id) VALUES(?,?,?)",origin.runId(),form.requestId(),requirementId);
            return detail(form.requestId(),merchant);
        }); } catch(org.springframework.dao.DuplicateKeyException e) {
            // 另一个需求的并发请求可能抢用同一标识；事务已回滚后再判断重放。
            var repeated=replayFor(form,hash,requirementId,merchant,origin);
            if(repeated!=null)return repeated;
            throw new BusinessException(409,"草稿编号发生冲突，请重试本次保存");
        }
    }
}
