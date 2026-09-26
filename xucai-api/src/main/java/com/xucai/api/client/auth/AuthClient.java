package com.xucai.api.client.auth;

import com.xucai.api.dto.auth.RoleDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;



@FeignClient(contextId = "auth1111", value = "xucai-auth")
public interface AuthClient {

    @GetMapping("/roles/{id}")
    RoleDTO queryRoleById(@PathVariable("id") Long id);


}
