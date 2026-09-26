package com.xucai.trade.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.common.exceptions.BusinessException;
import com.xucai.trade.domain.dto.FulfillmentForms.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import java.util.Set;

@Service @RequiredArgsConstructor
public class PurchaseFulfillmentService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final PurchaseOrderService orders;
    private Map<String,Object> lock(String id,long user,boolean merchant) {
        var rows=jdbc.queryForList("SELECT * FROM purchase_order WHERE id=?"+(merchant?"":" AND user_id=?")+" FOR UPDATE",merchant?new Object[]{id}:new Object[]{id,user});
        if(rows.isEmpty())throw new BusinessException(404,"订单不存在或不属于当前账户");
        return rows.get(0);
    }
    private String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}
    }
    private void noOpenCase(String id) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_after_sale WHERE order_id=? AND status='OPEN'",Integer.class,id)>0)
            throw new BusinessException(409,"此订单有待处理售后，请先处理再继续履约");
    }
    public Map<String,Object> ship(String id,long merchant,Shipment form) {
        var normalized=new Shipment(form.carrier().trim(),form.trackingNo().trim(),form.note().trim());
        return transactions.execute(tx->{
            var order=lock(id,merchant,true);
            if(order.get("shipment_json")!=null) {
                Shipment saved;
                try{saved=json.readValue((String)order.get("shipment_json"),Shipment.class);}catch(Exception e){throw new IllegalStateException("物流记录损坏",e);}
                if(!saved.equals(normalized))throw new BusinessException(409,"订单已登记另一份物流信息，原记录未更改");
                return orders.detail(id,merchant,true);
            }
            if(!"PAID".equals(order.get("status")))throw new BusinessException(409,"仅已支付订单可以登记发货");
            noOpenCase(id);
            int changed=jdbc.update("UPDATE purchase_order SET status='SHIPPED',shipment_json=?,shipped_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='PAID'",encode(normalized),id);
            if(changed!=1)throw new BusinessException(409,"订单状态变化，请刷新");
            return orders.detail(id,merchant,true);
        });
    }
    public Map<String,Object> complete(String id,long buyer) {
        return transactions.execute(tx->{
            var order=lock(id,buyer,false);
            if("COMPLETED".equals(order.get("status")))return orders.detail(id,buyer,false);
            if(!"SHIPPED".equals(order.get("status")))throw new BusinessException(409,"仅已发货订单可以确认收货");
            noOpenCase(id);
            int changed=jdbc.update("UPDATE purchase_order SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='SHIPPED'",id);
            if(changed!=1)throw new BusinessException(409,"订单状态变化，请刷新");
            return orders.detail(id,buyer,false);
        });
    }
    public Map<String,Object> apply(String id,long buyer,Apply form) {
        String requestId=form.requestId().toLowerCase(java.util.Locale.ROOT),reason=form.reason().trim();
        return transactions.execute(tx->{
            var order=lock(id,buyer,false);
            var existing=jdbc.queryForList("SELECT * FROM purchase_after_sale WHERE id=?",requestId);
            if(!existing.isEmpty()) {
                var saved=existing.get(0);
                if(!id.equals(saved.get("order_id"))||!reason.equals(saved.get("reason")))throw new BusinessException(409,"申请标识已使用且内容不同，请核对原申请");
                return orders.detail(id,buyer,false);
            }
            if(!Set.of("PAID","SHIPPED","COMPLETED").contains(order.get("status")))throw new BusinessException(409,"仅已支付或履约中的订单可以申请售后");
            noOpenCase(id);
            try {jdbc.update("INSERT INTO purchase_after_sale(id,order_id,reason) VALUES(?,?,?)",requestId,id,reason);}
            catch(org.springframework.dao.DuplicateKeyException e){throw new BusinessException(409,"申请标识已使用，请核对原申请");}
            return orders.detail(id,buyer,false);
        });
    }
    public Map<String,Object> resolve(String id,String caseId,long merchant,Resolve form) {
        String note=form.resolution().trim();
        return transactions.execute(tx->{
            lock(id,merchant,true);
            var rows=jdbc.queryForList("SELECT * FROM purchase_after_sale WHERE id=? AND order_id=?",caseId,id);
            if(rows.isEmpty())throw new BusinessException(404,"售后申请不存在或不属于此订单");
            var saved=rows.get(0);
            if(!"OPEN".equals(saved.get("status"))) {
                if(!form.status().equals(saved.get("status"))||!note.equals(saved.get("resolution")))throw new BusinessException(409,"此售后已有处理结论，原记录未更改");
                return orders.detail(id,merchant,true);
            }
            int changed=jdbc.update("UPDATE purchase_after_sale SET status=?,resolution=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP(3) WHERE id=? AND order_id=? AND status='OPEN'",form.status(),note,merchant,caseId,id);
            if(changed!=1)throw new BusinessException(409,"售后状态变化，请刷新");
            return orders.detail(id,merchant,true);
        });
    }
}
