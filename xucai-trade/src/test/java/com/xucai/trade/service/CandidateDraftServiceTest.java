package com.xucai.trade.service;
import com.xucai.api.client.aigc.CandidateRunClient;
import com.xucai.api.client.catalog.CatalogClient;
import com.xucai.common.exceptions.BusinessException;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CandidateDraftServiceTest {
    @Test void replayNeedsNeitherLiveRequirementNorRemoteServiceAndKeepsFirstCreator(){
        var drafts=mock(PlanDraftService.class);var requirements=mock(RequirementService.class);var ai=mock(CandidateRunClient.class);var catalog=mock(CatalogClient.class);var validator=mock(Validator.class);
        var original=Map.<String,Object>of("id","plan","createdBy",10002L,"effectiveStatus","STALE");when(drafts.candidateReplay("run",8,10003)).thenReturn(original);
        assertSame(original,new CandidateDraftService(drafts,requirements,ai,catalog,validator).adopt(8,10003,"run"));verifyNoInteractions(requirements,ai,catalog,validator);
    }
    @Test void replayCannotBeMovedToAnotherRequirement(){
        var drafts=mock(PlanDraftService.class);var requirements=mock(RequirementService.class);var ai=mock(CandidateRunClient.class);var catalog=mock(CatalogClient.class);var validator=mock(Validator.class);
        when(drafts.candidateReplay("run",9,10002)).thenThrow(new BusinessException(409,"另一需求"));
        assertThrows(BusinessException.class,()->new CandidateDraftService(drafts,requirements,ai,catalog,validator).adopt(9,10002,"run"));verifyNoInteractions(requirements,ai,catalog,validator);
    }
}
