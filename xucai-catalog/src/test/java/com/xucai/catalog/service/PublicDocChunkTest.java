package com.xucai.catalog.service;

import com.xucai.common.exceptions.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicDocChunkTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final DocChunkService service=spy(new DocChunkService(jdbc,mock(TransactionTemplate.class)));
    void published(){when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(24L))).thenReturn(true);}
    @Test void hiddenProductCannotReachSearchOrLocate(){
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(24L))).thenReturn(false);
        assertThrows(BusinessException.class,()->service.publicSearch(24L,null,1,12));
        assertThrows(BusinessException.class,()->service.publicLocate(24L,"doc:1:v1:c1"));
        verify(service,never()).search(any());verify(service,never()).locate(anyString());
    }
    @Test void publicSearchNeverRequestsUnpublished(){
        published();var result=new LinkedHashMap<String,Object>();result.put("items",List.of());result.put("dropped",List.of());
        doReturn(result).when(service).search(any());
        var actual=service.publicSearch(24L,"USB-C",1,12);
        verify(service).search(new DocChunkService.Query("USB-C",24L,null,null,false,1,12));
        assertEquals(true,actual.get("evidenceReady"));assertEquals(List.of(),actual.get("documentStates"));
    }
    @Test void historicalCrossProductAndFailedIntegrityNeverExposeLocateResult(){
        published();for(var result:List.of(
                Map.of("productId",25L,"current",true,"productStatus","PUBLISHED"),
                Map.of("productId",24L,"current",false,"productStatus","PUBLISHED"),
                Map.of("productId",24L,"current",true,"productStatus","HIDDEN"))){
            doReturn(result).when(service).locate("doc:1:v1:c1");
            assertThrows(BusinessException.class,()->service.publicLocate(24L,"doc:1:v1:c1"));
        }
    }
}
