package com.xucai.api.dto.mall;

public record RequirementDTO(Long id, Long userId, String title, String rawText,
        RequirementDetails structured, Long revision, String status, String createdAt, String updatedAt) { }
