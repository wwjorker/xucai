package com.xucai.trade.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.client.catalog.CatalogClient;
import com.xucai.api.dto.mall.*;
import com.xucai.common.exceptions.BusinessException;
import com.xucai.trade.domain.dto.PlanReviewForm;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service @RequiredArgsConstructor
public class PlanReleaseService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PlanDraftService drafts;
    private final RequirementService requirements;
    private final CatalogClient catalog;
    private final TransactionTemplate transactions;
    public record CurrentCheck(String status,List<String> reasons,String checkedAt) { }
    private String encode(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
    private PlanDraftRules.Snapshot snapshot(Map<String,Object> plan){return json.convertValue(plan.get("snapshot"),PlanDraftRules.Snapshot.class);}
    private Map<Long,SaleItemDTO> sales(PlanDraftRules.Snapshot snapshot,boolean lock) {
        Map<Long,SaleItemDTO> result=new HashMap<>();
        for(long id:snapshot.items().stream().map(PlanDraftRules.Item::productId).sorted().toList()) {
            var rows=jdbc.query("SELECT * FROM sale_item WHERE product_id=?"+(lock?" FOR UPDATE":""),(r,n)->new SaleItemDTO(r.getLong("product_id"),r.getLong("price"),r.getInt("stock"),r.getBoolean("on_sale"),r.getLong("sale_version")),id);
            if(!rows.isEmpty())result.put(id,rows.get(0));
        }
        return result;
    }
    private CurrentCheck check(Map<String,Object> plan,boolean merchant) {
        var snapshot=snapshot(plan);List<String> reasons=new ArrayList<>();boolean unavailable=false;
        if("ORDERED".equals(plan.get("status")))return new CurrentCheck("ORDERED",List.of("此方案已经成单，请查看订单中的约定与收货信息"),java.time.Instant.now().toString());
        if("STALE".equals(plan.get("effectiveStatus")))return new CurrentCheck("STALE",List.of("需求已变化或关闭，请基于最新需求重新编制"),java.time.Instant.now().toString());
        Map<Long,CatalogSnapshotDTO> products=new HashMap<>();
        for(var item:snapshot.items())try{products.put(item.productId(),merchant?catalog.snapshot(item.productId()):catalog.publicSnapshot(item.productId()));}
        catch(FeignException e){if(e.status()!=404)unavailable=true;}
        reasons.addAll(PlanReviewRules.catalog(snapshot,products));
        reasons.addAll(PlanReviewRules.business(snapshot,sales(snapshot,false)));
        var latest=requirements.get(snapshot.requirement().id(),snapshot.requirement().userId(),false);
        if(!"OPEN".equals(latest.status()) || !latest.revision().equals(snapshot.requirement().revision()))
            return new CurrentCheck("STALE",List.of("需求已变化或关闭，请重新编制并核实"),java.time.Instant.now().toString());
        if(unavailable)reasons.add("目录服务暂不可用，无法完成当前检查");
        return new CurrentCheck(unavailable?"UNAVAILABLE":reasons.isEmpty()?"READY":"BLOCKED",List.copyOf(reasons),java.time.Instant.now().toString());
    }
    public Map<String,Object> reviewPage(String id,long merchant) {
        var plan=drafts.detail(id,merchant);var snapshot=snapshot(plan);
        Map<String,Object> result=new LinkedHashMap<>();result.put("plan",plan);result.put("conditions",PlanReviewRules.conditions(snapshot));result.put("currentCheck",check(plan,true));
        var releases=jdbc.queryForList("SELECT plan_id FROM procurement_plan_release WHERE draft_id=?",id);
        result.put("releasedPlanId",releases.isEmpty()?null:releases.get(0).get("plan_id"));
        return result;
    }
    private Map<String,Object> released(String id,long user,boolean merchant) {
        // 先检查发布事实和归属，客户不能用草稿ID探测目录或读草稿。
        var rows=jdbc.queryForList("SELECT v.*,p.request_hash FROM procurement_plan_release v JOIN procurement_plan p ON p.id=v.plan_id JOIN procurement_requirement r ON r.id=p.requirement_id WHERE v.plan_id=?"+(merchant?"":" AND r.user_id=?"),merchant?new Object[]{id}:new Object[]{id,user});
        if(rows.isEmpty())throw new BusinessException(404,"待确认方案不存在或不属于当前账户");
        var row=rows.get(0);var plan=drafts.detail(id,user);
        try {plan.put("review",json.readTree(String.valueOf(row.get("review_json"))));}catch(Exception e){throw new IllegalStateException(e);}
        plan.put("draftId",row.get("draft_id"));plan.put("verifiedBy",row.get("verified_by"));plan.put("verifiedAt",String.valueOf(row.get("verified_at")));
        var orders=jdbc.queryForList("SELECT id FROM purchase_order WHERE plan_id=?",id);
        plan.put("orderId",orders.isEmpty()?null:orders.get(0).get("id"));
        return plan;
    }
    public Map<String,Object> detail(String id,long user,boolean merchant) {var plan=released(id,user,merchant);plan.put("currentCheck",check(plan,merchant));return plan;}
    public Map<String,Object> list(long requirementId,long buyer) {
        requirements.get(requirementId,buyer,false);
        var items=jdbc.queryForList("SELECT p.id,p.plan_no AS planNo,p.summary,p.total_amount AS totalAmount,p.requirement_revision AS requirementRevision,CASE WHEN p.status='ORDERED' THEN 'ORDERED' WHEN p.requirement_revision=r.revision AND r.status='OPEN' THEN p.status ELSE 'STALE' END AS effectiveStatus FROM procurement_plan p JOIN procurement_plan_release v ON v.plan_id=p.id JOIN procurement_requirement r ON r.id=p.requirement_id WHERE p.requirement_id=? AND r.user_id=? ORDER BY p.plan_no DESC LIMIT 50",requirementId,buyer);
        return Map.of("items",items);
    }
    private boolean replay(String draftId,long merchant,PlanReviewForm form,String hash) {
        var rows=jdbc.queryForList("SELECT p.request_hash,p.created_by,v.draft_id FROM procurement_plan p LEFT JOIN procurement_plan_release v ON v.plan_id=p.id WHERE p.id=?",form.requestId());
        if(rows.isEmpty())return false;
        var row=rows.get(0);
        if(!hash.equals(row.get("request_hash")) || !draftId.equals(row.get("draft_id")) || ((Number)row.get("created_by")).longValue()!=merchant)throw new BusinessException(409,"发布标识已用于其他内容，请查看已有版本");
        return true;
    }
    public Map<String,Object> publish(String draftId,long merchant,PlanReviewForm form) {
        String hash;try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((draftId+encode(form)).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
        if(replay(draftId,merchant,form,hash))return released(form.requestId(),merchant,true);
        var draft=drafts.detail(draftId,merchant);
        if(!"DRAFT".equals(draft.get("status")))throw new BusinessException(409,"只能核实未发布的草稿");
        var snap=snapshot(draft);var review=PlanReviewRules.review(snap,form);
        var check=check(draft,true);
        if(!"READY".equals(check.status()))throw new BusinessException("UNAVAILABLE".equals(check.status())?503:409,String.join("；",check.reasons()));
        try {transactions.executeWithoutResult(tx->{
            jdbc.queryForList("SELECT id FROM procurement_requirement WHERE id=? FOR UPDATE",snap.requirement().id());
            if(replay(draftId,merchant,form,hash))return;
            var current=requirements.get(snap.requirement().id(),merchant,true);
            if(!"OPEN".equals(current.status()) || !current.revision().equals(snap.requirement().revision()))throw new BusinessException(409,"需求已变化，请重新编制");
            var reasons=PlanReviewRules.business(snap,sales(snap,true));
            if(!reasons.isEmpty())throw new BusinessException(409,String.join("；",reasons));
            if(!jdbc.queryForList("SELECT plan_id FROM procurement_plan_release WHERE draft_id=?",draftId).isEmpty())throw new BusinessException(409,"此草稿已经发布，请查看已有版本；修改内容需新草稿");
            int no=jdbc.queryForObject("SELECT COALESCE(MAX(plan_no),0)+1 FROM procurement_plan WHERE requirement_id=?",Integer.class,current.id());
            var confirmed=new PlanDraftRules.Snapshot(snap.requirement(),snap.items(),List.of("商家已按逐项说明人工核实，引用支持关系仍以人工判断为准；价格、库存与资料需在下单时复查。"),snap.aiOrigin());
            jdbc.update("INSERT INTO procurement_plan(id,request_hash,requirement_id,requirement_revision,plan_no,status,summary,total_amount,snapshot_json,created_by,source) VALUES(?,?,?,?,?,'PENDING_CONFIRM',?,?,?,?,?)",form.requestId(),hash,current.id(),current.revision(),no,draft.get("summary"),PlanDraftRules.total(snap.items()),encode(confirmed),merchant,draft.get("source"));
            jdbc.update("INSERT INTO procurement_plan_release(plan_id,draft_id,review_json,verified_by) VALUES(?,?,?,?)",form.requestId(),draftId,encode(review),merchant);
        });}catch(org.springframework.dao.DuplicateKeyException e){if(!replay(draftId,merchant,form,hash))throw new BusinessException(409,"此草稿已产生待确认版本，请刷新查看");}
        return released(form.requestId(),merchant,true);
    }
}
