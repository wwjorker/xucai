package com.xucai.api.client.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name="xucai-ai",contextId="assistantDraft")
public interface AssistantDraftClient {
    @GetMapping("/assistant/sessions/{session}/turns/{turn}")
    JsonNode turn(@PathVariable("session") String session,@PathVariable("turn") String turn,
                  @RequestHeader("user-info") long user,@RequestHeader("role-info") long role);
}
