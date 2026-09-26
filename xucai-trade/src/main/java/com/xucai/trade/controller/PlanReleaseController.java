package com.xucai.trade.controller;
import com.xucai.trade.service.PlanReleaseService;
import com.xucai.trade.domain.dto.PlanReviewForm;
import com.xucai.common.utils.MallIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @Validated @RequiredArgsConstructor
public class PlanReleaseController {
 private final PlanReleaseService service;
 @GetMapping("/admin/plans/{id}/review") public Map<String,Object> review(@PathVariable @Size(max=36) String id){return service.reviewPage(id,MallIdentity.merchant());}
 @PostMapping("/admin/plans/{id}/publish") public Map<String,Object> publish(@PathVariable @Size(max=36) String id,@RequestBody @Valid PlanReviewForm form){return service.publish(id,MallIdentity.merchant(),form);}
 @GetMapping("/admin/plans/{id}/released") public Map<String,Object> admin(@PathVariable @Size(max=36) String id){return service.detail(id,MallIdentity.merchant(),true);}
 @GetMapping("/plans/{id}") public Map<String,Object> detail(@PathVariable @Size(max=36) String id){return service.detail(id,MallIdentity.buyer(),false);}
 @GetMapping("/requirements/{id}/plans") public Map<String,Object> list(@PathVariable @Positive long id){return service.list(id,MallIdentity.buyer());}
}
