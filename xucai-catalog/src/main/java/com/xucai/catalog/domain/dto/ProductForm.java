package com.xucai.catalog.domain.dto;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
public record ProductForm(
    @NotBlank @Size(max=160) String name,
    @NotBlank @Size(max=80) String brand,
    @NotBlank @Size(max=120) String series,
    @NotNull @Min(1) @Max(6) Integer categoryId,
    @Size(max=1000) String imageUrl,
    @NotBlank @Size(max=4000) String description,
    @NotNull JsonNode specJson,
    @Positive Long expectedSpecVersion
) { }
