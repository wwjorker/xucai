package com.xucai.trade.controller;

import com.xucai.api.dto.mall.RequirementDTO;
import com.xucai.common.utils.MallIdentity;
import com.xucai.trade.domain.dto.RequirementForm;
import com.xucai.trade.service.AssistantRequirementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/requirements/from-assistant/{session}/{turn}")
public class AssistantRequirementController {
    private final AssistantRequirementService service;
    @GetMapping public AssistantRequirementService.Link get(@PathVariable String session,@PathVariable String turn){return service.find(MallIdentity.buyer(),session,turn);}
    @PostMapping public RequirementDTO create(@PathVariable String session,@PathVariable String turn,@Valid @RequestBody RequirementForm form){return service.create(MallIdentity.buyer(),session,turn,form);}
}
