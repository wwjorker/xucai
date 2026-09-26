package com.xucai.trade.domain.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
public record PlanDraftForm(
        @NotBlank @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
        @NotNull @Positive Long expectedRequirementRevision,
        @NotBlank @Size(max=500) String summary,
        @NotNull @Size(min=1,max=20) List<@NotNull @Valid Item> items) {
    public record Item(@NotNull @Positive Long productId, @NotNull @Positive Long expectedSpecVersion,
            @NotNull @Positive Long expectedSaleVersion, @NotNull @Min(1) @Max(10000) Integer quantity,
            @NotNull @Size(max=3) List<@NotNull @Valid Evidence> evidence) { }
    public record Evidence(@NotNull @Positive Long docId, @NotNull @Positive Long version,
            @NotBlank @Size(max=1000) String quote, @NotBlank @Size(max=500) String note,
            @Valid Chunk chunk) { }
    /**
     * 可选：这条摘录是从哪个资料片段选出来的。缺省为null，旧的手工摘录照常保存。
     * 保存前核验目录里的真实片段，随后冻结原文、规则、位置与摘录；历史回放不再依赖片段表。
     */
    public record Chunk(@NotBlank @Size(max=80) @Pattern(regexp="doc:[0-9]{1,18}:v[0-9]{1,18}:c[1-9][0-9]{0,8}") String chunkId, @NotBlank @Size(max=20) String ruleVersion,
            @NotNull @Min(0) Integer charStart, @NotNull @Positive Integer charEnd) { }
}
