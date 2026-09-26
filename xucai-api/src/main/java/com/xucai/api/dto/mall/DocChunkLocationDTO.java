package com.xucai.api.dto.mall;

/** 目录端核验后的片段位置；交易只在保存前读取，历史方案不依赖它回放。 */
public record DocChunkLocationDTO(String chunkId, Long productId, Long docId, Long version,
        String ruleVersion, Integer charStart, Integer charEnd, String text, String content,
        Boolean located, Boolean current, String indexState, String productStatus) { }
