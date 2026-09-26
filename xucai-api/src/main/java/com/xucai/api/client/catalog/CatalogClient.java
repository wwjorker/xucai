package com.xucai.api.client.catalog;
import com.xucai.api.dto.mall.ProductStateDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
@FeignClient(name="xucai-catalog", contextId="catalog")
public interface CatalogClient {
    @GetMapping("/admin/doc-chunks/locate")
    com.xucai.api.dto.mall.DocChunkLocationDTO locateChunk(@org.springframework.web.bind.annotation.RequestParam("chunkId") String chunkId);
    @GetMapping("/products/{id}/snapshot")
    com.xucai.api.dto.mall.CatalogSnapshotDTO publicSnapshot(@PathVariable("id") Long id);
    @GetMapping("/admin/products/{id}/snapshot")
    com.xucai.api.dto.mall.CatalogSnapshotDTO snapshot(@PathVariable("id") Long id);
    @GetMapping("/admin/products/{id}/state")
    ProductStateDTO state(@PathVariable("id") Long id);
}
