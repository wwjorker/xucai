package com.xucai.aigc.procurement;
import com.xucai.api.dto.mall.RequirementDTO;
import org.springframework.stereotype.Component;

/** 正常环境没有模型执行授权；不注入ChatClient，不发送任何模型请求。 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="xucai.procurement.model.enabled",havingValue="false",matchIfMissing=true)
public class DisabledRequirementAnalyzer implements RequirementAnalyzer {
    @Override public AnalysisResult analyze(RequirementDTO input){throw new ModelUnavailableException();}
    public static class ModelUnavailableException extends RuntimeException { }
}
