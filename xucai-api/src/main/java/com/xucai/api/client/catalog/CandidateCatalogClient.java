package com.xucai.api.client.catalog;

import com.xucai.api.dto.mall.CandidateCatalogDTO.*;
import com.xucai.api.dto.mall.DocChunkLocationDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name="xucai-catalog",contextId="candidateCatalog")
public interface CandidateCatalogClient {
    @GetMapping("/products/{id}/doc-chunks/snapshot")
    IndexSnapshot indexSnapshot(@PathVariable("id") Long id,@RequestHeader("user-info") Long user,@RequestHeader("role-info") Long role);
    @GetMapping("/products")
    Products list(@RequestParam("categoryId") Integer categoryId,@RequestParam("page") int page,@RequestParam("size") int size,
                  @RequestHeader("user-info") Long user,@RequestHeader("role-info") Long role);
    @GetMapping("/products/{id}")
    Product product(@PathVariable("id") Long id,@RequestHeader("user-info") Long user,@RequestHeader("role-info") Long role);
    @GetMapping("/products/{id}/doc-chunks")
    Chunks chunks(@PathVariable("id") Long id,@RequestParam("size") int size,
                  @RequestHeader("user-info") Long user,@RequestHeader("role-info") Long role);
    @GetMapping("/products/{id}/doc-chunks/locate")
    DocChunkLocationDTO locate(@PathVariable("id") Long id,@RequestParam("chunkId") String chunkId,
                              @RequestHeader("user-info") Long user,@RequestHeader("role-info") Long role);
}
