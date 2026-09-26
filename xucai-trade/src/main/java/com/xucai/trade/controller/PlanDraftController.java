package com.xucai.trade.controller;
import com.xucai.trade.service.PlanDraftService;
import com.xucai.trade.domain.dto.PlanDraftForm;
import com.xucai.common.utils.MallIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @Validated @RequiredArgsConstructor
public class PlanDraftController {
    private final PlanDraftService service;
    @PostMapping("/admin/requirements/{id}/plans")
    public Map<String,Object> create(@PathVariable @Positive long id,@RequestBody @Valid PlanDraftForm form){return service.create(id,MallIdentity.merchant(),form);}
    @GetMapping("/admin/requirements/{id}/plans")
    public Map<String,Object> list(@PathVariable @Positive long id,@RequestParam(defaultValue="1") @Min(1) @Max(100000) int page,@RequestParam(defaultValue="10") @Min(1) @Max(50) int size){return service.list(id,MallIdentity.merchant(),page,size);}
    @GetMapping("/admin/plans/{id}")
    public Map<String,Object> detail(@PathVariable @Size(max=36) String id){return service.detail(id,MallIdentity.merchant());}
}
