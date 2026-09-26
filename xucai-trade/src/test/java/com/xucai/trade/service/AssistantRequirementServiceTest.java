package com.xucai.trade.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.client.ai.AssistantDraftClient;
import com.xucai.api.dto.mall.RequirementDTO;
import com.xucai.trade.domain.dto.RequirementForm;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantRequirementServiceTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final RequirementService requirements=mock(RequirementService.class);
    final AssistantDraftClient drafts=mock(AssistantDraftClient.class);
    final TransactionTemplate tx=mock(TransactionTemplate.class);
    final AssistantRequirementService service=new AssistantRequirementService(jdbc,requirements,drafts,tx);
    final String session="12fb9f0e-1c9f-4197-85d8-68265498c189",turn="0920270e-f552-4792-a9ae-761e6f5dbf6c";
    final RequirementForm form=new RequirementForm("title","text",null,null);
    final RequirementDTO saved=new RequirementDTO(42L,7L,"saved","text",null,1L,"CLOSED","now","now");
    void validDraft()throws Exception{
        when(drafts.turn(session,turn,7,2)).thenReturn(new ObjectMapper().readTree("{\"status\":\"DONE\",\"result\":{\"requirementDraft\":{}}}"));
        when(tx.execute(any())).thenAnswer(i->((TransactionCallback<?>)i.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
    }
    @Test void replayReturnsExistingEvenIfAiUnavailableAndRequirementClosed(){
        when(jdbc.query(anyString(),any(RowMapper.class),eq(7L),eq(session),eq(turn))).thenReturn(List.of(42L));
        when(requirements.get(42,7,false)).thenReturn(saved);
        assertSame(saved,service.create(7,session,turn,form));verifyNoInteractions(drafts,tx);verify(requirements,never()).create(anyLong(),any());
    }
    @Test void initialSaveLinksCreatedRequirementInTransaction()throws Exception{
        validDraft();when(requirements.create(7,form)).thenReturn(saved);
        assertSame(saved,service.create(7,session,turn,form));
        verify(jdbc).update(startsWith("UPDATE requirement_assistant_source"),eq(42L),eq(7L),eq(session),eq(turn));
    }
    @Test void concurrentLoserReturnsWinnerWithoutSecondCreate()throws Exception{
        validDraft();when(jdbc.queryForObject(contains("FOR UPDATE"),eq(Long.class),eq(7L),eq(session),eq(turn))).thenReturn(42L);
        when(requirements.get(42,7,false)).thenReturn(saved);
        assertSame(saved,service.create(7,session,turn,form));verify(requirements,never()).create(anyLong(),any());
    }
    @Test void incompleteOrUnavailableDraftCannotEnterWriteTransaction()throws Exception{
        when(drafts.turn(session,turn,7,2)).thenReturn(new ObjectMapper().readTree("{\"status\":\"FAILED\"}"));
        assertThrows(RuntimeException.class,()->service.create(7,session,turn,form));verifyNoInteractions(tx,requirements);
    }
    @Test void malformedIdentifiersAndOldRevisionAreRejectedBeforeLookup(){
        assertThrows(RuntimeException.class,()->service.find(7,"invalid",turn));
        assertThrows(RuntimeException.class,()->service.create(7,session,turn,new RequirementForm("t","x",null,1L)));verifyNoInteractions(jdbc,drafts,tx,requirements);
    }
    @Test void lookupIsScopedToCurrentAccount(){
        assertNull(service.find(8,session,turn).requirementId());
        verify(jdbc).query(anyString(),any(RowMapper.class),eq(8L),eq(session),eq(turn));verifyNoInteractions(drafts,tx,requirements);
    }
}
