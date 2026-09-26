package com.xucai.common.utils;

import com.xucai.common.exceptions.BusinessException;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 两服务协作序列化发布/隐藏/售卖变更。事务必须在 action 返回前提交。 */
@RequiredArgsConstructor
public class ProductMutationLock {
    private final RedissonClient redis;
    public <T> T execute(long productId, Supplier<T> action) {
        RLock lock = redis.getLock("xucai:product-mutation:" + productId);
        boolean acquired;
        try {
            // 不指定固定lease，使用Redisson watchdog；锁故障时禁止继续写。
            acquired = lock.tryLock(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "商品操作被中断，请重试");
        } catch (RuntimeException e) {
            throw new BusinessException(503, "商品变更协调暂不可用，请稍后重试");
        }
        if (!acquired) throw new BusinessException(409, "该商品正在变更，请刷新后重试");
        try { return action.get(); }
        finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
    }
}
