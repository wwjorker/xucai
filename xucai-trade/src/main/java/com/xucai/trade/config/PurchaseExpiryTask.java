package com.xucai.trade.config;

import com.xucai.trade.service.PurchasePaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@Configuration
@Profile("xucai")
@EnableScheduling
@RequiredArgsConstructor
public class PurchaseExpiryTask {
    private final JdbcTemplate jdbc;
    private final PurchasePaymentService payments;

    // 每轮最多100单，每单独立事务；启动后同样扫描持久化期限，恢复停机期间的过期单。
    @Scheduled(initialDelay=10000,fixedDelay=15000)
    public void scan() {
        var ids=jdbc.queryForList("SELECT id FROM purchase_order WHERE status='NO_PAY' AND expires_at<=CURRENT_TIMESTAMP(3) ORDER BY expires_at,id LIMIT 100",String.class);
        for(String id:ids)try { payments.expire(id); }
        catch(Exception e) { log.error("采购订单超时关闭失败，后续扫描重试，订单号={}",id,e); }
    }
}
