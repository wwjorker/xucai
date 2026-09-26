package com.xucai.trade.domain.dto;

import com.xucai.api.dto.mall.RequirementDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record RequirementForm(@NotBlank @Size(max=100) String title,
        @NotBlank @Size(max=4000) String rawText,
        @NotNull @Valid RequirementDetails structured, @Min(1) Long expectedRevision) { }
