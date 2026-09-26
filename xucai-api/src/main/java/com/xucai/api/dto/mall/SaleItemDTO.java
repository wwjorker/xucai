package com.xucai.api.dto.mall;
public record SaleItemDTO(Long productId, Long price, Integer stock, Boolean onSale, Long saleVersion) { }
