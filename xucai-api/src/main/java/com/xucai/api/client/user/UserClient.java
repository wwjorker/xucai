package com.xucai.api.client.user;
import com.xucai.api.client.user.fallback.UserClientFallback;
import com.xucai.api.dto.user.LoginFormDTO;
import com.xucai.common.domain.dto.LoginUserDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
@FeignClient(value = "xucai-user", fallbackFactory = UserClientFallback.class)
public interface UserClient {
    @PostMapping("/users/detail/{isStaff}")
    LoginUserDTO queryUserDetail(@RequestBody LoginFormDTO loginDTO, @PathVariable("isStaff") boolean isStaff);
}
