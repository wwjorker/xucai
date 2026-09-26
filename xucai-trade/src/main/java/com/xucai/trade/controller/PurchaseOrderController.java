package com.xucai.trade.controller;

import com.xucai.common.utils.MallIdentity;
import com.xucai.trade.domain.dto.PurchaseOrderForm;
import com.xucai.trade.service.PurchaseOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @Validated @RequiredArgsConstructor
public class PurchaseOrderController {
    private final PurchaseOrderService service;
    private final com.xucai.trade.service.PurchasePaymentService payments;
    @PostMapping("/purchase-orders/{id}/demo-pay")
    public Map<String,Object> pay(@PathVariable @Size(max=36) String id) { return payments.pay(id,MallIdentity.buyer()); }
    @PostMapping("/purchase-orders/{id}/cancel")
    public Map<String,Object> cancel(@PathVariable @Size(max=36) String id) { return payments.cancel(id,MallIdentity.buyer()); }
    @PostMapping("/plans/{id}/confirm")
    public Map<String,Object> confirm(@PathVariable @Size(max=36) String id,@RequestBody @Valid PurchaseOrderForm form) {
        return service.confirm(id,MallIdentity.buyer(),form);
    }
    @GetMapping("/purchase-orders/{id}")
    public Map<String,Object> detail(@PathVariable @Size(max=36) String id) {return service.detail(id,MallIdentity.buyer(),false);}
    @GetMapping("/admin/purchase-orders/{id}")
    public Map<String,Object> adminDetail(@PathVariable @Size(max=36) String id) {return service.detail(id,MallIdentity.merchant(),true);}
    @GetMapping("/purchase-orders")
    public Map<String,Object> list(@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,@RequestParam(required=false) @Positive Long requirementId) {
        return service.list(MallIdentity.buyer(),false,page,size,requirementId);
    }
    @GetMapping("/admin/purchase-orders")
    public Map<String,Object> adminList(@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,@RequestParam(required=false) @Positive Long requirementId) {
        return service.list(MallIdentity.merchant(),true,page,size,requirementId);
    }
}
