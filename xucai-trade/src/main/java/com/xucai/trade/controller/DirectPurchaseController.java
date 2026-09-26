package com.xucai.trade.controller;

import com.xucai.common.utils.MallIdentity;
import com.xucai.trade.domain.dto.DirectPurchaseForms.*;
import com.xucai.trade.service.DirectPurchaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @Validated @RequiredArgsConstructor
public class DirectPurchaseController {
    private final DirectPurchaseService service;
    @GetMapping("/cart") public DirectPurchaseService.CartView cart(){return service.getCart(MallIdentity.buyer());}
    @PutMapping("/cart") public DirectPurchaseService.CartView save(@RequestBody @Valid Cart form){return service.saveCart(MallIdentity.buyer(),form);}
    @PostMapping("/checkout-intents") public Map<String,Object> prepare(@RequestBody @Valid Prepare form){return service.prepare(MallIdentity.buyer(),form);}
    @GetMapping("/checkout-intents/{id}") public Map<String,Object> get(@PathVariable @Pattern(regexp="[0-9a-f-]{36}") String id){return service.getIntent(id,MallIdentity.buyer());}
    @PostMapping("/checkout-intents/{id}/confirm") public Map<String,Object> confirm(@PathVariable @Pattern(regexp="[0-9a-f-]{36}") String id){return service.confirm(id,MallIdentity.buyer());}
}
