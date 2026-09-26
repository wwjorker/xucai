package com.xucai.trade.domain.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
public record PlanReviewForm(
        @NotBlank @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
        @NotBlank @Size(max=1000) String note,
        @NotNull @Size(min=1,max=21) List<@NotNull @Valid Check> checks) {
    public record Check(@NotBlank @Size(max=40) String key,
                        @NotBlank @Pattern(regexp="MET|CONFLICT|UNKNOWN") String verdict,
                        @NotBlank @Size(max=1000) String reason,
                        @NotNull @Size(max=60) List<@NotNull @Valid Reference> references) { }
    public record Reference(@NotNull @Positive Long productId,@NotNull @Positive Long docId) { }
}
