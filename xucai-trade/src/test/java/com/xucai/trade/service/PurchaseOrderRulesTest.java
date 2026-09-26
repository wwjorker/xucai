package com.xucai.trade.service;
import com.xucai.trade.domain.dto.PurchaseOrderForm.Address;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PurchaseOrderRulesTest {
    private final Address saved=new Address("演示收件人","13800000000","演示路1号");
    @Test void trimsOnlyBoundaryWhitespace() {
        assertEquals(saved,PurchaseOrderRules.normalize(new Address(" 演示收件人 ","13800000000 "," 演示路1号")));
    }
    @Test void identicalNormalizedAddressReplays() {
        assertDoesNotThrow(()->PurchaseOrderRules.replayAddress(new Address(" 演示收件人","13800000000","演示路1号 "),saved));
    }
    @Test void eachAddressFieldIsPartOfReplayContract() {
        for(var address:new Address[]{new Address("另一人",saved.phone(),saved.detail()),new Address(saved.recipient(),"13900000000",saved.detail()),new Address(saved.recipient(),saved.phone(),"演示路2号")})
            assertThrows(com.xucai.common.exceptions.BusinessException.class,()->PurchaseOrderRules.replayAddress(address,saved));
    }
    @Test void newOrderRequiresOpenCurrentPublishedPlan() {
        assertDoesNotThrow(()->PurchaseOrderRules.confirmable("PENDING_CONFIRM","OPEN",2,2));
        for(String state:new String[]{"DRAFT","ORDERED","STALE"})assertThrows(com.xucai.common.exceptions.BusinessException.class,()->PurchaseOrderRules.confirmable(state,"OPEN",2,2));
    }
    @Test void closedOrderedOrRevisedRequirementBlocksNewOrder() {
        for(String state:new String[]{"CLOSED","ORDERED"})assertThrows(com.xucai.common.exceptions.BusinessException.class,()->PurchaseOrderRules.confirmable("PENDING_CONFIRM",state,2,2));
        assertThrows(com.xucai.common.exceptions.BusinessException.class,()->PurchaseOrderRules.confirmable("PENDING_CONFIRM","OPEN",2,3));
    }
}
