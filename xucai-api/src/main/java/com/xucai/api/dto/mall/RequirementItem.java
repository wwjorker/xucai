package com.xucai.api.dto.mall;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 每个分类的设备件数，null表示尚未确认；不是人数或套数。 */
public record RequirementItem(
        @NotNull @Min(1) @Max(6) Integer categoryId,
        @Min(1) @Max(10000) Integer quantity) { }
