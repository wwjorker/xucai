package com.xucai.trade.controller;
import com.xucai.trade.service.CandidateDraftService;
import com.xucai.common.utils.MallIdentity;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @Validated @RequiredArgsConstructor
public class CandidateDraftController {
    private final CandidateDraftService service;
    @PostMapping("/admin/requirements/{id}/candidate-plans/{runId}")
    public Map<String,Object> adopt(@PathVariable @Positive long id,@PathVariable @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String runId){return service.adopt(id,MallIdentity.merchant(),runId);}
}
