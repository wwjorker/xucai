package com.xucai.common.utils;
import com.xucai.common.exceptions.BusinessException;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductMutationLockTest {
    @Test
    void failureToAcquireNeverExecutesWrite() throws Exception {
        RedissonClient redis=mock(RedissonClient.class); RLock lock=mock(RLock.class);
        when(redis.getLock("xucai:product-mutation:7")).thenReturn(lock);
        assertThrows(BusinessException.class, () -> new ProductMutationLock(redis).execute(7,() -> fail("不能执行写入")));
        verify(lock,never()).unlock();
    }
    @Test
    void releasesAfterActionCompletesAndAlsoOnRollback() throws Exception {
        RedissonClient redis=mock(RedissonClient.class); RLock lock=mock(RLock.class);
        when(redis.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(3,TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        new ProductMutationLock(redis).execute(7,() -> { verify(lock,never()).unlock(); return 1; });
        verify(lock).unlock();
        assertThrows(BusinessException.class,() -> new ProductMutationLock(redis).execute(7,() -> { throw new BusinessException(409,"冲突"); }));
        verify(lock,times(2)).unlock();
    }
}
