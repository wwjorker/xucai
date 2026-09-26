package com.xucai.api.dto.mall;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.util.List;

/** 人工确认的采购条件；未知值保留null，不把未知当0或不需要。金额为分。 */
public record RequirementDetails(
        @Min(1) @Max(100000000) Long budgetCents,
        @Min(1) @Max(10000) Integer quantity,
        @NotNull @Size(max=6) List<@NotNull @Min(1) @Max(6) Integer> categoryIds,
        @NotNull @Size(max=1000) String usage,
        @NotNull @Size(max=20) List<@NotBlank @Size(max=200) String> mustHave,
        @NotNull @Size(max=20) List<@NotBlank @Size(max=200) String> optional,
        @Size(max=6) List<@NotNull @Valid RequirementItem> items) {
    /** 旧记录/旧调用仍可读写；items=null和显式空列表的含义不同。 */
    public RequirementDetails(Long budgetCents, Integer quantity, List<Integer> categoryIds,
                              String usage, List<String> mustHave, List<String> optional) {
        this(budgetCents, quantity, categoryIds, usage, mustHave, optional, null);
    }
}
