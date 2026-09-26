package com.xucai.api.dto.mall;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 客户可读取的公开目录数据，禁止借用商家管理接口。 */
public final class CandidateCatalogDTO {
    private CandidateCatalogDTO() { }
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Product(Long id,String name,Integer categoryId,Long specVersion,JsonNode specJson,
                          String status,Boolean priceAvailable,SaleItemDTO sale,String purchaseMessage) { }
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Products(List<Product> items,Long total) { }
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Chunk(String chunkId,Long productId,Long docId,Long version,String ruleVersion,
                        Integer charStart,Integer charEnd,String text,String docTitle,String sourceUrl) { }
    @JsonIgnoreProperties(ignoreUnknown=true)
    public record Chunks(List<Chunk> items,Long total,Boolean evidenceReady,List<java.util.Map<String,Object>> dropped) { }
    public record DocumentManifest(Long docId,Long version,String contentHash,String ruleVersion,Integer chunkCount) { }
    public record IndexSnapshot(Long productId,String productName,Integer categoryId,Long specVersion,
                                List<DocumentManifest> documents,List<Chunk> chunks) { }
}
