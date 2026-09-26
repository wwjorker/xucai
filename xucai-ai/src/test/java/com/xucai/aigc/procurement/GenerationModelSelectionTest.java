package com.xucai.aigc.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationModelSelectionTest {
    @Test void configuredModelIsUsedInRequestAndRunMetadata() throws Exception {
        var mapper=new ObjectMapper();
        for(String model:new String[]{"qwen-plus-2025-12-01","qwen3.7-flash-2026-07-15"}){
            var analyzer=new DashScopeRequirementAnalyzer(mock(ModelCallBudget.class),mock(jakarta.validation.Validator.class),mapper,"test-only",mock(CandidatePlanner.class),model);
            var body=mapper.readTree(DashScopeRequirementAnalyzer.requestBody("JSON",Map.of("rawText","公开演示条件"),mapper,model));
            assertEquals(model,analyzer.model()); assertEquals(model,body.path("model").asText());
            assertFalse(body.path("enable_thinking").asBoolean());
            assertEquals(2048,body.path("max_tokens").asInt());
            assertEquals("json_object",body.path("response_format").path("type").asText());
        }
    }
    @Test void unsupportedOrDriftingModelFailsBeforeAnyNetworkOrBudgetUse(){
        for(String model:new String[]{"qwen3.7-flash","qwen3.8-max",""," qwen-plus-2025-12-01"})
            assertThrows(IllegalArgumentException.class,()->DashScopeRequirementAnalyzer.validateModel(model));
    }
}
