package com.xucai.aigc.procurement;
import com.xucai.api.dto.mall.RequirementDTO;

/** 只接收固定版本快照；不能保存需求、商品、订单或调用写工具。 */
public interface RequirementAnalyzer {
    AnalysisResult analyze(RequirementDTO input);
    default AnalysisResult analyze(RequirementDTO input,String runId){return analyze(input);}
    default AnalysisResult candidates(RequirementDTO input,String runId){throw new DisabledRequirementAnalyzer.ModelUnavailableException();}
    default String model(){return "disabled";}
    default boolean available(){return false;}
}
