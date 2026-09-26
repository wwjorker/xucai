package com.xucai.catalog.domain.dto;
import jakarta.validation.constraints.*;
public record ProductDocForm(
    @NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,80}") String docKey,
    @NotNull @Min(0) Long expectedVersion,
    @NotBlank @Size(max=200) String title,
    @NotBlank @Size(max=1000) String sourceUrl,
    @NotBlank @Size(max=30000) String content
) { }
