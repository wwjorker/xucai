package com.xucai.catalog.domain.dto;
import jakarta.validation.constraints.*;
public record ProductStatusForm(@NotBlank @Pattern(regexp="PUBLISHED|HIDDEN") String status,
                                @NotNull @Positive Long expectedSpecVersion) { }
