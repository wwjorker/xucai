package com.xucai.aigc.procurement.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.common.exceptions.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantSessionStoreTest {
    static final String SESSION="00000000-0000-0000-0000-000000000001", TURN="00000000-0000-0000-0000-000000000002";
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final TransactionTemplate tx=mock(TransactionTemplate.class);
    final AssistantSessionStore store=new AssistantSessionStore(jdbc,tx,new ObjectMapper());
    AssistantSessionStoreTest(){when(tx.execute(any())).thenAnswer(i->((TransactionCallback<?>)i.getArgument(0)).doInTransaction(null));}
    void session(long revision,boolean archived){when(jdbc.query(startsWith("SELECT * FROM assistant_session WHERE id=? AND user_id=?"),any(org.springframework.jdbc.core.RowMapper.class),eq(SESSION),eq(7L)))
        .thenReturn(List.of(new AssistantSessionStore.Session(SESSION,"采购",revision,archived,"now")));}
    void previous(String text,String status){when(jdbc.query(eq("SELECT * FROM assistant_turn WHERE id=? AND session_id=? AND user_id=?"),any(org.springframework.jdbc.core.RowMapper.class),eq(TURN),eq(SESSION),eq(7L)))
        .thenReturn(List.of(new AssistantSessionStore.Turn(TURN,SESSION,1,text,status,"已完成",null,null,"now","now")));}
    @Test void rejectsAmbiguousIdsAndEmptyTextBeforeDatabase(){
        for(String id:Arrays.asList(null,"1","../secret","00000000-0000-0000-0000-00000000000x"))assertThrows(BusinessException.class,()->store.get(7,id));
        assertThrows(BusinessException.class,()->store.accept(7,SESSION,TURN," "));
        assertThrows(BusinessException.class,()->store.accept(7,SESSION,TURN,"x".repeat(4001)));
        verifyNoInteractions(jdbc);
    }
    @Test void anotherAccountCannotReadOrEditOrEnumerateTurns(){
        session(0,false);
        assertEquals(404,assertThrows(BusinessException.class,()->store.get(8,SESSION)).getStatus());
        assertThrows(BusinessException.class,()->store.edit(8,SESSION,0,"other",null));
        assertThrows(BusinessException.class,()->store.turns(8,SESSION));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void staleRenameDoesNotOverwrite(){session(2,false);
        assertEquals(409,assertThrows(BusinessException.class,()->store.edit(7,SESSION,1,"new",null)).getStatus());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void exactRetryReturnsSavedTurnWithoutStartingAgain(){session(2,true);previous("第二款要4个","DONE");
        var accepted=store.accept(7,SESSION,TURN,"第二款要4个");assertFalse(accepted.created());assertEquals("DONE",accepted.turn().status());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void retryCannotChangePurchaseInstruction(){session(2,false);previous("第二款要4个","RUNNING");
        assertEquals(409,assertThrows(BusinessException.class,()->store.accept(7,SESSION,TURN,"第二款要40个")).getStatus());
    }
    @Test void archivedSessionRejectsNewTurn(){session(2,true);
        assertEquals(409,assertThrows(BusinessException.class,()->store.accept(7,SESSION,TURN,"你好")).getStatus());
    }
    @Test void runningTurnBlocksSecondTurnAndArchive(){session(2,false);
        when(jdbc.queryForObject(startsWith("SELECT EXISTS"),eq(Boolean.class),eq(SESSION),eq(7L))).thenReturn(true);
        assertEquals(409,assertThrows(BusinessException.class,()->store.accept(7,SESSION,TURN,"你好")).getStatus());
        assertEquals(409,assertThrows(BusinessException.class,()->store.edit(7,SESSION,2,null,true)).getStatus());
    }
    @Test void completedOrCanceledTurnCannotBeOverwritten(){
        assertFalse(store.finish(7,SESSION,TURN,"DONE","迟到回复",Map.of(),null));
        verify(jdbc).update(contains("AND status='RUNNING'"),eq("DONE"),eq("迟到回复"),eq("{}"),isNull(),eq(TURN),eq(SESSION),eq(7L));
        assertThrows(IllegalArgumentException.class,()->store.finish(7,SESSION,TURN,"RUNNING",null,null,null));
        assertThrows(IllegalArgumentException.class,()->store.finish(7,SESSION,TURN,"DONE","",null,null));
    }
    @Test void titleValidationAndRequestContentAreSeparate(){
        assertEquals("采购",AssistantSessionStore.title(" 采购 "));
        assertEquals(" 2个 ",AssistantSessionStore.text(" 2个 "));
        assertThrows(BusinessException.class,()->AssistantSessionStore.title("x".repeat(81)));
    }
}
