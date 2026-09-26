package com.xucai.trade.service;

import com.xucai.common.exceptions.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PurchasePaymentService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PurchaseOrderService orders;

    public Map<String,Object> pay(String id,long buyer) {
        return transactions.execute(tx -> {
            var order=lock(id,buyer);
            if(java.util.Set.of("PAID","SHIPPED","COMPLETED").contains(order.get("status")))return orders.detail(id,buyer,false);
            // DB时间为准；即使扫描滞后或支付请求曾等待行锁，也不得支付过期订单。
            int changed=jdbc.update("UPDATE purchase_order SET status='PAID',pay_channel='DEMO',paid_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='NO_PAY' AND expires_at>CURRENT_TIMESTAMP(3)",id);
            if(changed!=1)throw new BusinessException(409,"订单已关闭、已履约或支付期限已到，请刷新订单");
            return orders.detail(id,buyer,false);
        });
    }
    public Map<String,Object> cancel(String id,long buyer) {
        return transactions.execute(tx -> {
            var order=lock(id,buyer);
            if("CLOSED".equals(order.get("status")))return orders.detail(id,buyer,false);
            if(!"NO_PAY".equals(order.get("status")))throw new BusinessException(409,"已支付订单不能取消，请刷新订单");
            close(id,false);
            return orders.detail(id,buyer,false);
        });
    }
    private Map<String,Object> lock(String id,long buyer) {
        var rows=jdbc.queryForList("SELECT status FROM purchase_order WHERE id=? AND user_id=? FOR UPDATE",id,buyer);
        if(rows.isEmpty())throw new BusinessException(404,"订单不存在或不属于当前账户");
        return rows.get(0);
    }
    public void expire(String id) {
        transactions.executeWithoutResult(tx -> {
            // 与用户操作锁同一订单，随后仅按SKU升序取售卖锁，不反向锁需求/方案。
            var rows=jdbc.queryForList("SELECT status FROM purchase_order WHERE id=? FOR UPDATE",id);
            if(!rows.isEmpty() && "NO_PAY".equals(rows.get(0).get("status")))close(id,true);
        });
    }
    private void close(String id,boolean timeout) {
        int changed=jdbc.update("UPDATE purchase_order SET status='CLOSED',closed_at=CURRENT_TIMESTAMP(3),close_reason=CASE WHEN expires_at<=CURRENT_TIMESTAMP(3) THEN 'TIMEOUT' ELSE 'CUSTOMER' END WHERE id=? AND status='NO_PAY'"+(timeout?" AND expires_at<=CURRENT_TIMESTAMP(3)":""),id);
        if(changed==0)return;
        var items=jdbc.queryForList("SELECT product_id,quantity FROM purchase_order_item WHERE order_id=? ORDER BY product_id",id);
        if(items.isEmpty())throw new IllegalStateException("订单无明细，关闭已回滚");
        for(var item:items) {
            int quantity=((Number)item.get("quantity")).intValue();
            // 与数据库100万上限一致；任何缺失/越界失败都连同关闭状态整单回滚。
            int restored=quantity>0?jdbc.update("UPDATE sale_item SET stock=stock+? WHERE product_id=? AND stock<=?",quantity,item.get("product_id"),1000000-quantity):0;
            if(restored!=1)throw new IllegalStateException("库存归还失败，关闭已回滚："+id);
        }
    }
}
