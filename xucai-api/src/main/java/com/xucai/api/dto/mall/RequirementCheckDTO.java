package com.xucai.api.dto.mall;

import java.util.List;

public record RequirementCheckDTO(Long requirementId, Long revision, String status,
        String source, boolean complete, List<MissingField> missing) {
    public record MissingField(String code, String field, Integer categoryId, String message) { }
}
