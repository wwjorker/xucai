package com.xucai.catalog.controller;

import com.xucai.common.utils.MallIdentity;
import com.xucai.catalog.service.DocChunkService;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 商家侧资料片段入口：查找、定位原文、查看索引状态、整版重建。
 * 全部在 /admin/** 下，网关按角色拦截，服务内再核一次商家身份。
 */
@RestController
@RequiredArgsConstructor
@Validated
public class DocChunkController {
    private final DocChunkService service;

    /** 整版只读快照供采购索引构建；超出限额拒绝，不把分页结果当完整资料。 */
    @GetMapping("/products/{id}/doc-chunks/snapshot")
    public Map<String,Object> publicSnapshot(@PathVariable @Positive long id) {
        return service.publicSnapshot(id);
    }

    @GetMapping("/products/{id}/doc-chunks")
    public Map<String,Object> publicSearch(@PathVariable @Positive long id,
            @RequestParam(required=false) @Size(max=120) String keyword,
            @RequestParam(defaultValue="1") @Min(1) @Max(100000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(50) int size) {
        return service.publicSearch(id,keyword,page,size);
    }
    @GetMapping("/products/{id}/doc-chunks/locate")
    public Map<String,Object> publicLocate(@PathVariable @Positive long id,
            @RequestParam @NotBlank @Size(max=80) String chunkId) {
        return service.publicLocate(id,chunkId.trim());
    }

    @GetMapping("/admin/doc-chunks")
    public Map<String, Object> search(@RequestParam(required = false) @Size(max = 120) String keyword,
                                      @RequestParam(required = false) @Positive Long productId,
                                      @RequestParam(required = false) @Min(1) @Max(6) Integer categoryId,
                                      @RequestParam(required = false) @Size(max = 80) String docKey,
                                      @RequestParam(defaultValue = "false") boolean includeUnpublished,
                                      @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        MallIdentity.merchant();
        return service.search(new DocChunkService.Query(keyword, productId, categoryId, docKey, includeUnpublished, page, size));
    }

    @GetMapping("/admin/doc-chunks/locate")
    public Map<String, Object> locate(@RequestParam @NotBlank @Size(max = 80) String chunkId) {
        MallIdentity.merchant();
        return service.locate(chunkId.trim());
    }

    @GetMapping("/admin/products/{id}/doc-index")
    public List<Map<String, Object>> states(@PathVariable @Positive long id) {
        MallIdentity.merchant();
        return service.productStates(id);
    }

    /** 整版重建：同一份资料重复重建不会累积重复片段，稳定ID保持不变。 */
    @PostMapping("/admin/products/{id}/docs/{docId}/reindex")
    public Map<String, Object> reindex(@PathVariable @Positive long id, @PathVariable @Positive long docId) {
        MallIdentity.merchant();
        return service.rebuild(id, docId);
    }
}
