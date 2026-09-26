package com.xucai.api.client.user.fallback;
import com.xucai.api.client.user.UserClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
@Slf4j
public class UserClientFallback implements FallbackFactory<UserClient> {
    @Override public UserClient create(Throwable cause) {
        log.error("查询用户服务出现异常", cause);
        return (loginDTO, isStaff) -> null;
    }
}
