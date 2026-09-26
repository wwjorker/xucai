package com.xucai.aigc.procurement;
import com.xucai.api.dto.mall.RequirementDTO;

public record RunDTO(String id, Long userId, Long requirementId, Long requirementRevision,
        String status, String model, String promptVersion, RequirementDTO input, AnalysisResult result,
        String errorCode, String errorMessage, String startedAt, String finishedAt) { }
