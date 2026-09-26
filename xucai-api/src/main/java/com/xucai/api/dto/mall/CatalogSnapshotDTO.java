package com.xucai.api.dto.mall;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
/** 目录库同一读取事务的规格与有效资料；不回调交易查价格。 */
public record CatalogSnapshotDTO(Long id, String name, Integer categoryId, JsonNode specJson,
        Long specVersion, String status, List<Document> documents) {
    public record Document(Long id, Long version, String title, String sourceUrl, String content) { }
}
