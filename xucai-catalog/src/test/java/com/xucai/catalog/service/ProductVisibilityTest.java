package com.xucai.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.client.trade.SaleClient;
import com.xucai.api.dto.mall.SaleBatchDTO;
import com.xucai.common.exceptions.BusinessException;
import com.xucai.common.utils.ProductMutationLock;
import com.xucai.catalog.domain.vo.ProductVO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProductVisibilityTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SaleClient sales = mock(SaleClient.class);
    private final ProductService service = new ProductService(jdbc, new ObjectMapper(), sales,
            mock(ProductMutationLock.class), mock(TransactionTemplate.class), mock(DocChunkService.class));

    private ProductVO product(String status) {
        ProductVO product = new ProductVO();
        product.setId(24L);
        product.setStatus(status);
        when(jdbc.query(eq("SELECT * FROM product WHERE id=?"), any(RowMapper.class), eq(24L)))
                .thenReturn(List.of(product));
        return product;
    }

    @Test void publicDetailAndDocumentsRejectEveryNonPublishedStateBeforeReadingRelatedData() {
        for (String status : new String[]{"DRAFT", "HIDDEN", "UNKNOWN", null}) {
            product(status);
            assertEquals(404, assertThrows(BusinessException.class, () -> service.detail(24L, false)).getStatus());
            assertEquals(404, assertThrows(BusinessException.class, () -> service.docs(24L, false)).getStatus());
        }
        verifyNoInteractions(sales);
        verify(jdbc, never()).queryForList(anyString(), eq(24L));
    }

    @Test void publishedProductRemainsPublicAndOnlyActiveDocumentsAreRead() {
        ProductVO product = product("PUBLISHED");
        when(sales.batch(List.of(24L))).thenReturn(new SaleBatchDTO(true, List.of()));
        List<Map<String,Object>> docs = List.of(Map.of("id", 7L, "status", "ACTIVE"));
        when(jdbc.queryForList(contains("AND status='ACTIVE'"), eq(24L))).thenReturn(docs);
        assertSame(product, service.detail(24L, false));
        assertSame(docs, service.docs(24L, false));
        verify(sales).batch(List.of(24L));
        verify(jdbc).queryForList(contains("AND status='ACTIVE'"), eq(24L));
    }

    @Test void merchantCanStillInspectDraftAndHiddenProductsAndAllDocumentVersions() {
        when(sales.batch(List.of(24L))).thenReturn(new SaleBatchDTO(true, List.of()));
        List<Map<String,Object>> docs = List.of(Map.of("id", 7L, "status", "SUPERSEDED"));
        when(jdbc.queryForList(argThat((String sql) -> !sql.contains("AND status='ACTIVE'")), eq(24L))).thenReturn(docs);
        for (String status : List.of("DRAFT", "HIDDEN")) {
            assertSame(product(status), service.detail(24L, true));
            assertSame(docs, service.docs(24L, true));
        }
    }

    @Test void imagesRequireCurrentAssociationAndPublishedStatus() {
        String file="12345678-1234-1234-1234-123456789abc.png";
        ProductVO p=product("PUBLISHED");
        p.setImageUrl(ProductImageRef.url(24,"oss:products/"+file));
        assertEquals("products/"+file,service.imageKey(24,file,false));
        assertEquals(404,assertThrows(BusinessException.class,()->service.imageKey(24,"../secret",false)).getStatus());
        p.setStatus("HIDDEN");
        assertEquals(404,assertThrows(BusinessException.class,()->service.imageKey(24,file,false)).getStatus());
        assertEquals("products/"+file,service.imageKey(24,file,true));
        p.setImageUrl(null);
        assertEquals(404,assertThrows(BusinessException.class,()->service.imageKey(24,file,true)).getStatus());
        verifyNoInteractions(sales);
    }
}
