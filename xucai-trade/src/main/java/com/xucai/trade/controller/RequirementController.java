package com.xucai.trade.controller;

import com.xucai.api.dto.mall.RequirementDTO;
import com.xucai.api.dto.mall.RequirementCheckDTO;
import com.xucai.trade.service.RequirementRules;
import com.xucai.common.utils.MallIdentity;
import com.xucai.trade.domain.dto.RequirementForm;
import com.xucai.trade.service.RequirementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @Validated @RequiredArgsConstructor
public class RequirementController {
    private final RequirementService service;
    public record StatusForm(@NotNull @Min(1) Long expectedRevision,@NotBlank String status) { }
    @GetMapping("/requirements")
    public Map<String,Object> list(@RequestParam(defaultValue="1") @Min(1) @Max(100000) int page,@RequestParam(defaultValue="10") @Min(1) @Max(50) int size){return service.list(MallIdentity.buyer(),false,page,size);}
    @GetMapping("/requirements/{id}")
    public RequirementDTO get(@PathVariable @Positive long id){return service.get(id,MallIdentity.buyer(),false);}
    @GetMapping("/requirements/{id}/check")
    public RequirementCheckDTO check(@PathVariable @Positive long id){return RequirementRules.check(service.get(id,MallIdentity.buyer(),false));}
    @PostMapping("/requirements")
    public RequirementDTO create(@RequestBody @Valid RequirementForm form){return service.create(MallIdentity.buyer(),form);}
    @PutMapping("/requirements/{id}")
    public RequirementDTO update(@PathVariable @Positive long id,@RequestBody @Valid RequirementForm form){return service.update(id,MallIdentity.buyer(),form);}
    @PutMapping("/requirements/{id}/status")
    public RequirementDTO status(@PathVariable @Positive long id,@RequestBody @Valid StatusForm form){return service.status(id,MallIdentity.buyer(),form.expectedRevision(),form.status());}
    @GetMapping("/admin/requirements")
    public Map<String,Object> adminList(@RequestParam(defaultValue="1") @Min(1) @Max(100000) int page,@RequestParam(defaultValue="10") @Min(1) @Max(50) int size){return service.list(MallIdentity.merchant(),true,page,size);}
    @GetMapping("/admin/requirements/{id}")
    public RequirementDTO adminGet(@PathVariable @Positive long id){return service.get(id,MallIdentity.merchant(),true);}
    @GetMapping("/admin/requirements/{id}/check")
    public RequirementCheckDTO adminCheck(@PathVariable @Positive long id){return RequirementRules.check(service.get(id,MallIdentity.merchant(),true));}
}
