package com.xucai.aigc.procurement.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.common.exceptions.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetrievalRequestReplayTest {
    final ObjectMapper mapper=mapper();
    static ObjectMapper mapper(){var b=new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder();new com.xucai.common.autoconfigure.mvc.JsonConfig().jackson2ObjectMapperBuilderCustomizer().customize(b);return b.build();}
    String canonical(){return RetrievalRules.hash(RetrievalRules.json(new TreeMap<>(Map.of("products",List.of(1L,2L),"query","原查询","topK",3)),mapper));}
    @Test void canonicalIsFixedWithActualLongSerializer(){
        assertEquals("{\"products\":[\"1\",\"2\"],\"query\":\"原查询\",\"topK\":3}",RetrievalRules.json(new TreeMap<>(Map.of("topK",3,"query","原查询","products",List.of(1L,2L))),mapper));
        assertTrue(RetrievalRules.legacySearchHashes(List.of(1L,2L),"原查询",3,mapper).contains(canonical()));
    }
    @Test void allLegacyOrdersReplayWithoutChangingOldRows(){
        Set<String> hashes=RetrievalRules.legacySearchHashes(List.of(1L,2L),"原查询",3,mapper);assertEquals(6,hashes.size());
        for(String oldHash:hashes){
            var jdbc=mock(JdbcTemplate.class);when(jdbc.update(anyString(),any(Object[].class))).thenThrow(new org.springframework.dao.DuplicateKeyException("existing"));
            when(jdbc.queryForMap(anyString(),eq("id"))).thenReturn(Map.of("created_by",1L,"kind","SEARCH","request_hash",oldHash));
            var store=new RetrievalStore(jdbc,mock(TransactionTemplate.class),mapper);
            assertFalse(store.begin("id",1L,"SEARCH",canonical(),hashes));
            verify(jdbc,times(1)).update(startsWith("INSERT INTO retrieval_operation"),any(Object[].class));
            assertThrows(BusinessException.class,()->store.begin("id",2L,"SEARCH",canonical(),hashes));
            assertThrows(BusinessException.class,()->store.begin("id",1L,"BUILD",canonical(),hashes));
        }
    }
    @Test void changesToValuesNeverBecomeCompatible(){
        var original=RetrievalRules.legacySearchHashes(List.of(1L,2L),"原查询",3,mapper);
        for(var other:List.of(RetrievalRules.legacySearchHashes(List.of(2L,1L),"原查询",3,mapper),RetrievalRules.legacySearchHashes(List.of(1L,2L),"原查询 ",3,mapper),RetrievalRules.legacySearchHashes(List.of(1L,2L),"原查询",4,mapper)))assertTrue(Collections.disjoint(original,other));
    }
}
