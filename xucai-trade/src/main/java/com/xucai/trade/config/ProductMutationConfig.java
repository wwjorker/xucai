package com.xucai.trade.config;
import com.xucai.common.utils.ProductMutationLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class ProductMutationConfig {
    @Bean
    public ProductMutationLock productMutationLock(RedissonClient redis) { return new ProductMutationLock(redis); }
}
