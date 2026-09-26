package com.xucai.catalog.controller;
import com.xucai.api.dto.mall.ProductStateDTO;
import com.xucai.catalog.domain.dto.*;
import com.xucai.catalog.domain.vo.ProductVO;
import com.xucai.catalog.service.ProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequiredArgsConstructor
@Validated
public class ProductController {
    private final ProductService service;
    @GetMapping("/products/{id}/snapshot")
    public com.xucai.api.dto.mall.CatalogSnapshotDTO publicSnapshot(@PathVariable @Positive long id) {
        var snapshot=service.snapshot(id);
        if(!"PUBLISHED".equals(snapshot.status())) throw new com.xucai.common.exceptions.BusinessException(404,"商品未发布或已隐藏");
        return snapshot;
    }
    @GetMapping("/admin/products/{id}/snapshot")
    public com.xucai.api.dto.mall.CatalogSnapshotDTO snapshot(@PathVariable @Positive long id) {
        com.xucai.common.utils.MallIdentity.merchant();
        return service.snapshot(id);
    }
    @GetMapping("/products")
    public Map<String,Object> list(@RequestParam(defaultValue="1") @Min(1) @Max(100000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
            @RequestParam(required=false) @Min(1) @Max(6) Integer categoryId,
            @RequestParam(required=false) @Size(max=120) String keyword) {
        return service.list(false,page,size,categoryId,keyword);
    }
    @GetMapping("/products/{id}")
    public ProductVO detail(@PathVariable @Positive long id) { return service.detail(id,false); }
    @GetMapping("/products/{id}/docs")
    public List<Map<String,Object>> docs(@PathVariable @Positive long id) { return service.docs(id,false); }
    @GetMapping("/admin/products")
    public Map<String,Object> adminList(@RequestParam(defaultValue="1") @Min(1) @Max(100000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
            @RequestParam(required=false) @Min(1) @Max(6) Integer categoryId,
            @RequestParam(required=false) @Size(max=120) String keyword) {
        return service.list(true,page,size,categoryId,keyword);
    }
    @GetMapping("/admin/products/{id}")
    public ProductVO adminDetail(@PathVariable @Positive long id) { return service.detail(id,true); }
    @GetMapping("/admin/products/{id}/state")
    public ProductStateDTO state(@PathVariable @Positive long id) { return service.state(id); }
    @PostMapping("/admin/products")
    public ProductStateDTO create(@RequestBody @Valid ProductForm form) { return service.create(form); }
    @PutMapping("/admin/products/{id}")
    public ProductStateDTO update(@PathVariable @Positive long id, @RequestBody @Valid ProductForm form) { return service.update(id,form); }
    @PutMapping("/admin/products/{id}/status")
    public ProductStateDTO status(@PathVariable @Positive long id, @RequestBody @Valid ProductStatusForm form) { return service.changeStatus(id,form); }
    @GetMapping("/admin/products/{id}/docs")
    public List<Map<String,Object>> adminDocs(@PathVariable @Positive long id) { return service.docs(id,true); }
    @PostMapping("/admin/products/{id}/docs")
    public Map<String,Object> addDoc(@PathVariable @Positive long id, @RequestBody @Valid ProductDocForm form) { return service.addDoc(id,form); }
    @PutMapping("/admin/products/{id}/docs/{docId}/retire")
    public void retireDoc(@PathVariable @Positive long id, @PathVariable @Positive long docId) { service.retireDoc(id,docId); }
}
