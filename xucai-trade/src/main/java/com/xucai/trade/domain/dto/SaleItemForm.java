package com.xucai.trade.domain.dto;
import jakarta.validation.constraints.*;
public record SaleItemForm(
    @NotNull @Min(1) @Max(100000000) Long price,
    @NotNull @Min(0) @Max(1000000) Integer stock,
    @NotNull Boolean onSale,
    @NotNull @Min(0) Long expectedSaleVersion,
    @NotNull @Min(0) Integer expectedStock
) { }
