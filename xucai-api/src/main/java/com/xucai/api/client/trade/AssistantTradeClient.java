package com.xucai.api.client.trade;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Explicit actor arguments: the background assistant must not depend on request thread locals. */
@FeignClient(name="xucai-trade",contextId="assistantTrade")
public interface AssistantTradeClient {
    @GetMapping("/cart") JsonNode cart(@RequestHeader("user-info") long user,@RequestHeader("role-info") long role);
    @PutMapping("/cart") JsonNode saveCart(@RequestBody Map<String,Object> body,@RequestHeader("user-info") long user,@RequestHeader("role-info") long role);
    @GetMapping("/purchase-orders?page=1&size=10") JsonNode orders(@RequestHeader("user-info") long user,@RequestHeader("role-info") long role);
}
