package com.xucai.trade.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record PurchaseOrderForm(@NotNull @Valid Address address) {
    public record Address(
            @NotBlank @Size(max=40) String recipient,
            @NotBlank @Pattern(regexp="(?=(?:\\D*\\d){5})[0-9+() -]{5,30}") String phone,
            @NotBlank @Size(max=300) String detail) { }
}
