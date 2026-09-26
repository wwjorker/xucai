package com.xucai.api.config;
import com.xucai.api.client.user.fallback.UserClientFallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class FallbackConfig {
    @Bean public UserClientFallback userClientFallback() { return new UserClientFallback(); }
}
