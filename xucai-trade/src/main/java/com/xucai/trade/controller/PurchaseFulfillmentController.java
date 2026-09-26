package com.xucai.trade.controller;
import com.xucai.common.utils.MallIdentity;
import com.xucai.trade.domain.dto.FulfillmentForms.*;
import com.xucai.trade.service.PurchaseFulfillmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @Validated @RequiredArgsConstructor
public class PurchaseFulfillmentController {
    private final PurchaseFulfillmentService service;
    @PostMapping("/admin/purchase-orders/{id}/ship")
    public Map<String,Object> ship(@PathVariable @Size(max=36) String id,@RequestBody @Valid Shipment form) {return service.ship(id,MallIdentity.merchant(),form);}
    @PostMapping("/purchase-orders/{id}/complete")
    public Map<String,Object> complete(@PathVariable @Size(max=36) String id) {return service.complete(id,MallIdentity.buyer());}
    @PostMapping("/purchase-orders/{id}/after-sales")
    public Map<String,Object> apply(@PathVariable @Size(max=36) String id,@RequestBody @Valid Apply form) {return service.apply(id,MallIdentity.buyer(),form);}
    @PostMapping("/admin/purchase-orders/{id}/after-sales/{caseId}/resolve")
    public Map<String,Object> resolve(@PathVariable @Size(max=36) String id,@PathVariable @Size(max=36) String caseId,@RequestBody @Valid Resolve form) {return service.resolve(id,caseId,MallIdentity.merchant(),form);}
}
