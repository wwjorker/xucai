package com.xucai.trade.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.client.catalog.CatalogClient;
import com.xucai.common.exceptions.BusinessException;
import com.xucai.common.utils.ProductMutationLock;
import com.xucai.trade.domain.dto.DirectPurchaseForms.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DirectPurchaseServiceTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final CatalogClient catalog=mock(CatalogClient.class);
    final ProductMutationLock locks=mock(ProductMutationLock.class);
    final TransactionTemplate tx=mock(TransactionTemplate.class);
    final PurchaseOrderService orders=mock(PurchaseOrderService.class);
    final DirectPurchaseService service=new DirectPurchaseService(jdbc,new ObjectMapper(),catalog,locks,tx,orders);
    @Test void successfulConfirmationReplaysWithoutExternalDependenciesEvenAfterExpiry() throws Exception {
        ResultSet row=mock(ResultSet.class);
        when(row.getString("id")).thenReturn("intent");when(row.getLong("cart_revision")).thenReturn(1L);
        when(row.getString("order_id")).thenReturn("order");
        when(row.getString("address_json")).thenReturn("{\"recipient\":\"演示人\",\"phone\":\"13800000000\",\"detail\":\"演示路\"}");
        when(row.getString("snapshot_json")).thenReturn("{\"summary\":\"普通采购\",\"items\":[]}");
        when(row.getTimestamp("expires_at")).thenReturn(new Timestamp(0));
        when(jdbc.query(contains("checkout_intent"),any(RowMapper.class),eq("intent"),eq(42L)))
                .thenAnswer(i->List.of(((RowMapper<?>)i.getArgument(1)).mapRow(row,0)));
        var expected=Map.<String,Object>of("id","order");when(orders.detail("order",42L,false)).thenReturn(expected);
        assertSame(expected,service.confirm("intent",42L));verifyNoInteractions(catalog,locks,tx);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void anotherOwnerCannotConfirmAnIntent(){
        assertThrows(BusinessException.class,()->service.confirm("not-owned",42));
        verifyNoInteractions(catalog,locks,tx,orders);
    }
    @Test void readingEmptyCartDoesNotCreateRows(){
        assertEquals(new DirectPurchaseService.CartView(0,List.of()),service.getCart(42));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void staleCartCannotIssueAnUpdate() throws Exception {
        ResultSet row=mock(ResultSet.class);when(row.getLong("revision")).thenReturn(3L);when(row.getString("items_json")).thenReturn("[]");
        when(tx.execute(any())).thenAnswer(i->((TransactionCallback<?>)i.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        when(jdbc.query(contains("procurement_cart"),any(RowMapper.class),eq(42L)))
                .thenAnswer(i->List.of(((RowMapper<?>)i.getArgument(1)).mapRow(row,0)));
        assertThrows(BusinessException.class,()->service.saveCart(42,new Cart(2L,List.of(new Line(7L,1)))));
        verify(jdbc,never()).update(startsWith("UPDATE"),any(Object[].class));
        verifyNoInteractions(catalog,locks,orders);
    }
}
