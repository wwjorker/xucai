package com.xucai.trade.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.*;
import com.xucai.common.exceptions.BusinessException;
import com.xucai.trade.domain.dto.DirectPurchaseForms.Line;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DirectPurchaseRulesTest {
    private final CatalogSnapshotDTO product=new CatalogSnapshotDTO(7L,"鼠标",3,new ObjectMapper().createObjectNode(),2L,"PUBLISHED",List.of());
    private final SaleItemDTO sale=new SaleItemDTO(7L,4500L,4,true,3L);
    @Test void rejectsDuplicateIdsRatherThanDoubleCountingOrDiscardingQuantity(){
        assertThrows(BusinessException.class,()->DirectPurchaseRules.normalize(List.of(new Line(7L,1),new Line(7L,2))));
    }
    @Test void boundsAndSortsItemsForConsistentLockOrder(){
        assertEquals(1L,DirectPurchaseRules.normalize(List.of(new Line(7L,2),new Line(1L,3))).get(0).productId());
        for(int quantity:new int[]{0,-1,1000})assertThrows(BusinessException.class,()->DirectPurchaseRules.normalize(List.of(new Line(7L,quantity))));
        assertThrows(BusinessException.class,()->DirectPurchaseRules.normalize(Collections.nCopies(21,new Line(7L,1))));
    }
    @Test void staleTabsCannotOverwriteCart(){assertThrows(BusinessException.class,()->DirectPurchaseRules.version(2,3));}
    @Test void directSnapshotDoesNotPretendMerchantReviewOrEvidence(){
        var item=DirectPurchaseRules.item(new Line(7L,4),product,sale);
        assertTrue(item.evidence().isEmpty());assertEquals(18000,PlanDraftRules.total(List.of(item)));
    }
    @Test void unavailableHiddenAndInsufficientStockReject(){
        assertThrows(BusinessException.class,()->DirectPurchaseRules.item(new Line(7L,5),product,sale));
        assertThrows(BusinessException.class,()->DirectPurchaseRules.item(new Line(7L,1),product,null));
        assertThrows(BusinessException.class,()->DirectPurchaseRules.item(new Line(7L,1),new CatalogSnapshotDTO(7L,"鼠标",3,product.specJson(),2L,"HIDDEN",List.of()),sale));
    }
    @Test void changedPriceOrVersionRequiresNewConfirmation(){
        var item=DirectPurchaseRules.item(new Line(7L,4),product,sale);
        assertThrows(BusinessException.class,()->DirectPurchaseRules.confirm(item,product,new SaleItemDTO(7L,4600L,4,true,3L)));
        assertThrows(BusinessException.class,()->DirectPurchaseRules.confirm(item,product,new SaleItemDTO(7L,4500L,4,true,4L)));
        assertThrows(BusinessException.class,()->DirectPurchaseRules.confirm(item,new CatalogSnapshotDTO(7L,"鼠标",3,product.specJson(),3L,"PUBLISHED",List.of()),sale));
    }
    @Test void stockReductionAllowedOnlyIfStillEnough(){
        var item=DirectPurchaseRules.item(new Line(7L,2),product,sale);
        assertDoesNotThrow(()->DirectPurchaseRules.confirm(item,product,new SaleItemDTO(7L,4500L,2,true,3L)));
        assertThrows(BusinessException.class,()->DirectPurchaseRules.confirm(item,product,new SaleItemDTO(7L,4500L,1,true,3L)));
    }
}
