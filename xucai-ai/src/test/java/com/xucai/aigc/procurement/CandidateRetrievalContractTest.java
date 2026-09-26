package com.xucai.aigc.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CandidateRetrievalContractTest {
    final ObjectMapper mapper=projectMapper();
    static ObjectMapper projectMapper(){var builder=new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder();new com.xucai.common.autoconfigure.mvc.JsonConfig().jackson2ObjectMapperBuilderCustomizer().customize(builder);return builder.build();}
    @Test void oldResultHasNoInventedRetrievalAndNewTraceSurvivesMerchantDto() throws Exception {
        var old=mapper.readValue("{\"status\":\"NO_SELECTION\",\"items\":[],\"totalAmount\":0,\"consideredProducts\":2,\"partialPool\":false,\"semantic\":false,\"notice\":\"旧参考\"}",CandidateProposal.class);
        assertNull(old.retrieval());assertFalse(old.semantic());
        var trace=new CandidateRetrievalDTO("candidate-vector-v1","text-embedding-v4",1024,"run","hash",3,List.of(new CandidateRetrievalDTO.ProductScope(2,"generation","hash",List.of(new CandidateRetrievalDTO.Hit("chunk",1,.1)),List.of("chunk"))));
        var newer=new CandidateProposal("NO_SELECTION",List.of(),0,2,true,true,"参考",trace);
        assertEquals(trace,mapper.convertValue(newer,CandidateRunDTO.Candidate.class).retrieval());
        assertEquals(newer,mapper.readValue(mapper.writeValueAsString(newer),CandidateProposal.class));
    }
    @Test void unavailableGenerationBudgetStopsBeforeRetrieval(){
        var budget=mock(ModelCallBudget.class);var planner=mock(CandidatePlanner.class);
        var analyzer=new DashScopeRequirementAnalyzer(budget,mock(jakarta.validation.Validator.class),mapper,"test-only",planner,DashScopeRequirementAnalyzer.MODEL);
        assertThrows(ModelCallException.class,()->analyzer.candidates(null,"run"));verifyNoInteractions(planner);verify(budget,never()).reserve(anyString(),any(),anyString());
    }
    @Test void canceledRetrievalDoesNotReserveGeneration(){
        var budget=mock(ModelCallBudget.class);when(budget.available()).thenReturn(true);var planner=mock(CandidatePlanner.class);
        when(planner.prepare(any(),anyString())).thenThrow(new ModelCallException("MODEL_CANCELED","取消"));
        var analyzer=new DashScopeRequirementAnalyzer(budget,mock(jakarta.validation.Validator.class),mapper,"test-only",planner,DashScopeRequirementAnalyzer.MODEL);
        assertThrows(ModelCallException.class,()->analyzer.candidates(null,"run"));verify(budget,never()).reserve(anyString(),any(),anyString());
    }
}
