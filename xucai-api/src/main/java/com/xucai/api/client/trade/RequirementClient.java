package com.xucai.api.client.trade;

import com.xucai.api.dto.mall.RequirementDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name="xucai-trade",contextId="requirement")
public interface RequirementClient {
    // 异步线程显式传入已验证的客户身份，不依赖ThreadLocal继承。
    @GetMapping("/requirements/{id}")
    RequirementDTO get(@PathVariable("id") Long id, @RequestHeader("user-info") Long userId,
                       @RequestHeader("role-info") Long roleId);
}
