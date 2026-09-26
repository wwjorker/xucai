package com.xucai.trade.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.SaleItemDTO;
import com.xucai.common.exceptions.BusinessException;
import com.xucai.trade.domain.dto.PurchaseOrderForm;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PurchaseOrderService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PlanReleaseService releases;
    private final TransactionTemplate transactions;

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("订单序列化失败",e); }
    }
    private RowMapper<Map<String,Object>> row(boolean full) {
        return (r,n) -> {
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("id",r.getString("id")); result.put("userId",r.getLong("user_id"));
            result.put("planId",r.getString("plan_id")); result.put("requirementId",r.getObject("requirement_id"));
            result.put("orderSource",r.getString("plan_id")==null?"DIRECT":"PLAN");
            result.put("status",r.getString("status")); result.put("totalAmount",r.getLong("total_amount"));
            result.put("createdAt",r.getTimestamp("created_at").toLocalDateTime().toString());
            for(String[] field:new String[][]{{"expires_at","expiresAt"},{"paid_at","paidAt"},{"closed_at","closedAt"},{"shipped_at","shippedAt"},{"completed_at","completedAt"}}) {
                var time=r.getTimestamp(field[0]);
                result.put(field[1],time==null?null:time.toLocalDateTime().toString());
            }
            result.put("payChannel",r.getString("pay_channel")); result.put("closeReason",r.getString("close_reason"));
            if(full) try {
                result.put("address",json.readTree(r.getString("address_json")));
                result.put("snapshot",json.readTree(r.getString("snapshot_json")));
                result.put("shipment",r.getString("shipment_json")==null?null:json.readTree(r.getString("shipment_json")));
            } catch(Exception e) { throw new IllegalStateException("订单快照损坏",e); }
            return result;
        };
    }
    public Map<String,Object> detail(String id,long user,boolean merchant) {
        var rows=jdbc.query("SELECT * FROM purchase_order WHERE id=?"+(merchant?"":" AND user_id=?"),row(true),merchant?new Object[]{id}:new Object[]{id,user});
        if(rows.isEmpty())throw new BusinessException(404,"订单不存在或不属于当前账户");
        var order=rows.get(0);
        order.put("afterSales",jdbc.query("SELECT * FROM purchase_after_sale WHERE order_id=? ORDER BY created_at DESC,id DESC",(r,n)->{
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("id",r.getString("id"));item.put("reason",r.getString("reason"));item.put("status",r.getString("status"));
            item.put("resolution",r.getString("resolution"));item.put("reviewedBy",r.getObject("reviewed_by"));
            item.put("createdAt",r.getTimestamp("created_at").toLocalDateTime().toString());
            var reviewed=r.getTimestamp("reviewed_at");item.put("reviewedAt",reviewed==null?null:reviewed.toLocalDateTime().toString());return item;
        },id));
        return order;
    }
    public Map<String,Object> list(long user,boolean merchant,int page,int size,Long requirementId) {
        String where=" WHERE 1=1"; List<Object> args=new ArrayList<>();
        if(!merchant){where+=" AND user_id=?";args.add(user);}
        if(requirementId!=null){where+=" AND requirement_id=?";args.add(requirementId);}
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order"+where,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        var items=jdbc.query("SELECT * FROM purchase_order"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",row(false),args.toArray());
        for(var item:items)item.put("openAfterSaleCount",jdbc.queryForObject("SELECT COUNT(*) FROM purchase_after_sale WHERE order_id=? AND status='OPEN'",Integer.class,item.get("id")));
        return Map.of("total",total,"items",items);
    }
    private Map<String,Object> replay(String planId,long buyer,PurchaseOrderForm.Address address) {
        var rows=jdbc.query("SELECT * FROM purchase_order WHERE plan_id=? AND user_id=?",row(true),planId,buyer);
        if(rows.isEmpty())return null;
        var order=rows.get(0);
        PurchaseOrderRules.replayAddress(address,json.convertValue(order.get("address"),PurchaseOrderForm.Address.class));
        return order;
    }
    public Map<String,Object> confirm(String planId,long buyer,PurchaseOrderForm form) {
        var address=PurchaseOrderRules.normalize(form.address());
        // 已成单先按归属查回：方案已ORDERED、目录停机或库存变动均不妨碍原请求重放。
        var existing=replay(planId,buyer,address);if(existing!=null)return existing;
        var plan=releases.detail(planId,buyer,false); // 归属验证早于外部目录读取，Feign不在行锁事务内。
        var check=(PlanReleaseService.CurrentCheck)plan.get("currentCheck");
        if(!"READY".equals(check.status())) {
            // 另一次确认可能在首次重放查询与目录检查之间成单。
            var repeated=replay(planId,buyer,address);if(repeated!=null)return repeated;
            throw new BusinessException("UNAVAILABLE".equals(check.status())?503:409,String.join("；",check.reasons()));
        }
        var snapshot=json.convertValue(plan.get("snapshot"),PlanDraftRules.Snapshot.class);
        Map<String,Object> orderSnapshot=new LinkedHashMap<>();
        orderSnapshot.put("summary",plan.get("summary"));orderSnapshot.put("planNo",plan.get("planNo"));
        orderSnapshot.put("requirement",snapshot.requirement());orderSnapshot.put("items",snapshot.items());
        orderSnapshot.put("review",plan.get("review"));
        orderSnapshot.put("source",plan.get("source"));
        // 成单时冻结原始候选及检索来源；旧订单不回填当前资料，重放始终返回原快照。
        if(snapshot.aiOrigin()!=null)orderSnapshot.put("aiOrigin",snapshot.aiOrigin());
        String orderId=UUID.randomUUID().toString();
        try {
            return transactions.execute(tx -> {
                // 与草稿/发布统一需求→方案→商品顺序。锁定查询直接使用当前行，避免RR快照旧读。
                var requirements=jdbc.queryForList("SELECT id,user_id,revision,status FROM procurement_requirement WHERE id=? FOR UPDATE",snapshot.requirement().id());
                if(requirements.isEmpty() || ((Number)requirements.get(0).get("user_id")).longValue()!=buyer)
                    throw new BusinessException(404,"需求不存在或不属于当前账户");
                var repeated=replay(planId,buyer,address);if(repeated!=null)return repeated;
                if(!jdbc.queryForList("SELECT id FROM purchase_order WHERE requirement_id=?",snapshot.requirement().id()).isEmpty())
                    throw new BusinessException(409,"这份需求已有订单，请查看原订单；另一方案未下单");
                var requirement=requirements.get(0);
                var plans=jdbc.queryForList("SELECT status,requirement_revision FROM procurement_plan WHERE id=? FOR UPDATE",planId);
                if(plans.isEmpty())throw new BusinessException(404,"方案不存在");
                PurchaseOrderRules.confirmable((String)plans.get(0).get("status"),(String)requirement.get("status"),
                        ((Number)plans.get(0).get("requirement_revision")).longValue(),((Number)requirement.get("revision")).longValue());
                Map<Long,SaleItemDTO> sales=new HashMap<>();
                for(var item:snapshot.items().stream().sorted(Comparator.comparing(PlanDraftRules.Item::productId)).toList()) {
                    var rows=jdbc.query("SELECT * FROM sale_item WHERE product_id=? FOR UPDATE",
                            (r,n)->new SaleItemDTO(r.getLong("product_id"),r.getLong("price"),r.getInt("stock"),r.getBoolean("on_sale"),r.getLong("sale_version")),item.productId());
                    if(!rows.isEmpty())sales.put(item.productId(),rows.get(0));
                }
                var reasons=PlanReviewRules.business(snapshot,sales);
                if(!reasons.isEmpty())throw new BusinessException(409,String.join("；",reasons));
                for(var item:snapshot.items()) {
                    int changed=jdbc.update("UPDATE sale_item SET stock=stock-? WHERE product_id=? AND stock>=?",item.quantity(),item.productId(),item.quantity());
                    if(changed!=1)throw new BusinessException(409,"库存已变化，订单未创建");
                }
                jdbc.update("INSERT INTO purchase_order(id,user_id,requirement_id,plan_id,total_amount,address_json,snapshot_json,expires_at) VALUES(?,?,?,?,?,?,?,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 30 MINUTE))",
                        orderId,buyer,snapshot.requirement().id(),planId,PlanDraftRules.total(snapshot.items()),encode(address),encode(orderSnapshot));
                for(var item:snapshot.items())jdbc.update("INSERT INTO purchase_order_item(order_id,product_id,quantity,unit_price) VALUES(?,?,?,?)",orderId,item.productId(),item.quantity(),item.unitPrice());
                jdbc.update("UPDATE procurement_plan SET status='ORDERED' WHERE id=?",planId);
                jdbc.update("UPDATE procurement_requirement SET status='ORDERED' WHERE id=?",snapshot.requirement().id());
                return detail(orderId,buyer,false);
            });
        } catch(DuplicateKeyException e) {
            // 异常离开事务、扣库存已回滚后，才查回唯一键对应的原订单。
            var repeated=replay(planId,buyer,address);if(repeated!=null)return repeated;
            throw new BusinessException(409,"这份需求已经成单，请查看订单列表");
        }
    }
}
