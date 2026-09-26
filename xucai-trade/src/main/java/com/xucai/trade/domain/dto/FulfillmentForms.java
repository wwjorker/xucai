package com.xucai.trade.domain.dto;
import jakarta.validation.constraints.*;

public final class FulfillmentForms {
    private FulfillmentForms() { }
    public record Shipment(@NotBlank @Size(max=80) String carrier,
                           @NotBlank @Size(max=80) String trackingNo,
                           @NotNull @Size(max=500) String note) { }
    public record Apply(@NotBlank @Pattern(regexp="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String requestId,
                        @NotBlank @Size(max=1000) String reason) { }
    public record Resolve(@NotBlank @Pattern(regexp="RESOLVED|REJECTED") String status,
                          @NotBlank @Size(max=2000) String resolution) { }
}
