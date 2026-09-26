package com.xucai.aigc.procurement;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ModelCallBudgetTest {
    @Test void availabilityRequiresLedgerButNotHistoricalAllowance(){
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT COUNT(*)=1 FROM ai_model_budget WHERE id=?",Boolean.class,"requirement-pilot-v1")).thenReturn(true);
        assertTrue(new ModelCallBudget(jdbc,null).available());
    }
    @Test void databaseFailureDoesNotPermitUnmeteredCall(){
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq("requirement-pilot-v1"))).thenThrow(new DataAccessResourceFailureException("offline"));
        assertFalse(new ModelCallBudget(jdbc,null).available());
    }
    @Test void duplicateCallRollsBackAccounting(){
        var jdbc=mock(JdbcTemplate.class);
        var tm=mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status=new org.springframework.transaction.support.SimpleTransactionStatus();
        when(tm.getTransaction(any())).thenReturn(status);
        when(jdbc.update("UPDATE ai_model_budget SET used_calls=used_calls+1,reserved_micro_yuan=reserved_micro_yuan+? WHERE id=?",20000L,"requirement-pilot-v1")).thenReturn(1);
        when(jdbc.update(startsWith("INSERT INTO ai_model_call"),eq("duplicate"),eq(1L),eq(2L),eq("model"),eq(20000L))).thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));
        var input=new com.xucai.api.dto.mall.RequirementDTO(1L,7L,"test","test",null,2L,"OPEN",null,null);
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->new ModelCallBudget(jdbc,new org.springframework.transaction.support.TransactionTemplate(tm)).reserve("duplicate",input,"model"));
        verify(tm).rollback(status);verify(tm,never()).commit(any());
    }
}
