package com.xucai.aigc.procurement.retrieval;

import com.xucai.aigc.procurement.ModelCallException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcEmbeddingLedgerTest {
    @Test void personalQueryIsRecordedWithoutUpdatingHistoricalPilot(){
        var jdbc=mock(JdbcTemplate.class);var ledger=new JdbcEmbeddingLedger(jdbc,null);
        String id="00000000-0000-4000-8000-000000000001:101";
        ledger.reserveAssistant(id,12,4096);
        verify(jdbc).update(startsWith("INSERT INTO retrieval_model_call"),eq(id),eq(12),eq(EmbeddingClient.MODEL),eq(4096L));verifyNoMoreInteractions(jdbc);
        assertThrows(IllegalArgumentException.class,()->ledger.reserveAssistant(id.replace(":101",":104"),12,4096));
    }
    @Test void usageAndCallAreRecordedInSameTransactionWithoutCaps() {
        var jdbc=mock(JdbcTemplate.class);var tm=mock(PlatformTransactionManager.class);
        var status=new SimpleTransactionStatus();when(tm.getTransaction(any())).thenReturn(status);
        when(jdbc.update(startsWith("UPDATE ai_model_budget"),eq(4096L))).thenReturn(1);
        when(jdbc.update(startsWith("UPDATE retrieval_budget"),eq(0),eq(1),eq(4096L))).thenReturn(1);
        new JdbcEmbeddingLedger(jdbc,new TransactionTemplate(tm)).reserve("run:0","query",1,12,4096L);
        verify(jdbc).update(eq("UPDATE retrieval_budget SET used_calls=used_calls+1,used_documents=used_documents+?,used_queries=used_queries+?,reserved_micro_yuan=reserved_micro_yuan+? WHERE id='embedding-pilot-v1'"),eq(0),eq(1),eq(4096L));
        verify(jdbc).update(eq("UPDATE ai_model_budget SET reserved_micro_yuan=reserved_micro_yuan+? WHERE id='requirement-pilot-v1'"),eq(4096L));
        verify(tm).commit(status);verify(tm,never()).rollback(any());
    }
    @Test void missingLedgerRollsBackAggregateAndDoesNotInsertCall() {
        var jdbc=mock(JdbcTemplate.class);var tm=mock(PlatformTransactionManager.class);
        var status=new SimpleTransactionStatus();when(tm.getTransaction(any())).thenReturn(status);
        when(jdbc.update(startsWith("UPDATE ai_model_budget"),eq(4096L))).thenReturn(1);
        assertThrows(ModelCallException.class,()->new JdbcEmbeddingLedger(jdbc,new TransactionTemplate(tm)).reserve("run:0","query",1,12,4096L));
        verify(jdbc).update(startsWith("UPDATE retrieval_budget"),eq(0),eq(1),eq(4096L));
        verify(tm).rollback(status);verify(tm,never()).commit(any());
        verify(jdbc,never()).update(startsWith("INSERT INTO retrieval_model_call"),any(),any(),any(),any(),any(),any());
    }
}
