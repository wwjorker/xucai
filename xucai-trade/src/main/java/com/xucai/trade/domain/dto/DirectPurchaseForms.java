package com.xucai.trade.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class DirectPurchaseForms {
    private DirectPurchaseForms() { }
    public record Line(@NotNull @Positive Long productId, @NotNull @Min(1) @Max(999) Integer quantity) { }
    public record Cart(@NotNull @Min(0) Long expectedVersion,
                       @NotNull @Size(max=20) List<@NotNull @Valid Line> items) { }
    public record Prepare(@NotNull @Min(0) Long expectedVersion,
                          @NotNull @Valid PurchaseOrderForm.Address address) { }
}
