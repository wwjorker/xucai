package com.xucai.aigc.procurement;
import com.xucai.api.dto.mall.RequirementDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record AnalysisResult(@NotNull @Valid RequirementDetails conditions,
        @NotNull @Size(max=10) List<@NotBlank @Size(max=300) String> questions,
        @Size(max=3) List<@NotBlank @Size(max=300) String> adjustments,
        @Valid CandidateProposal candidate) {
    public AnalysisResult { adjustments=adjustments==null?List.of():List.copyOf(adjustments); }
    public AnalysisResult(RequirementDetails conditions,List<String> questions){this(conditions,questions,List.of());}
    public AnalysisResult(RequirementDetails conditions,List<String> questions,List<String> adjustments){this(conditions,questions,adjustments,null);}
}
