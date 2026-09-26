package com.xucai.api.client.aigc;
import com.xucai.api.dto.mall.CandidateRunDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
@FeignClient(name="xucai-ai",contextId="candidateRun")
public interface CandidateRunClient {
    @GetMapping("/admin/requirements/{requirementId}/candidates/{runId}")
    CandidateRunDTO get(@PathVariable("requirementId") long requirementId,@PathVariable("runId") String runId);
}
