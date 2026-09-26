package com.xucai.aigc.procurement;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.common.utils.*;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MerchantCandidateControllerTest {
    @AfterEach void clear(){UserContext.removeUser();RoleContext.clear();}
    private RunDTO run(String status,String proposalStatus){var candidate=new CandidateProposal(proposalStatus,List.of(new CandidateProposal.Item(1,"商品",2,1,1,5000,1,List.of(),"UNKNOWN")),5000,6,true,false,"参考");return new RunDTO("run",10001L,48L,1L,status,"model","candidate-reference-v3",null,new AnalysisResult(null,List.of(),List.of(),candidate),null,null,"now","now");}
    @Test void onlyMerchantCanReadCandidate(){var store=mock(JdbcRunStore.class);var controller=new MerchantCandidateController(store,new ObjectMapper());assertThrows(RuntimeException.class,()->controller.list(48));UserContext.setUser(10001L);RoleContext.set(2L);assertThrows(RuntimeException.class,()->controller.get(48,"run"));verifyNoInteractions(store);}
    @Test void canceledFailedRunningAndNoSelectionNeverEligible(){for(var state:List.of("CANCELED","FAILED","RUNNING"))assertFalse(MerchantCandidateController.usable(run(state,"REFERENCE")));assertFalse(MerchantCandidateController.usable(run("DONE","NO_SELECTION")));assertTrue(MerchantCandidateController.usable(run("DONE","REFERENCE")));}
    @Test void merchantReceivesOriginalRunAndCandidateButNotRawInput(){var store=mock(JdbcRunStore.class);when(store.merchantCandidate(48,"run")).thenReturn(run("DONE","REFERENCE"));UserContext.setUser(10002L);RoleContext.set(1L);var result=new MerchantCandidateController(store,new ObjectMapper()).get(48,"run");assertEquals(48L,result.requirementId());assertEquals("UNKNOWN",result.candidate().items().get(0).verdict());assertEquals(5000,result.candidate().totalAmount());verify(store).merchantCandidate(48,"run");}
}
