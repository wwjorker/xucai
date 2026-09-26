package com.xucai.trade.controller;
import com.xucai.api.dto.mall.*;
import com.xucai.trade.domain.dto.SaleItemForm;
import com.xucai.trade.service.SaleItemService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequiredArgsConstructor
@Validated
public class SaleItemController {
    private final SaleItemService service;
    @GetMapping("/sale-items/batch")
    public SaleBatchDTO batch(@RequestParam @Size(min=1,max=100) List<@Positive Long> ids) { return service.batch(ids); }
    @GetMapping("/admin/sale-items/{id}")
    public SaleItemDTO get(@PathVariable @Positive long id) { return service.get(id); }
    @PutMapping("/admin/sale-items/{id}")
    public SaleItemDTO save(@PathVariable @Positive long id, @RequestBody @Valid SaleItemForm form) { return service.save(id,form); }
}
