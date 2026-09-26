package com.xucai.aigc.procurement.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssistantPurchaseScopeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void persistedBaselineExcludesExistingItemsButCountsAllNewAdditions() throws Exception {
        var original = AssistantPurchaseScope.capture(20000, 4, false,
                mapper.readTree("{\"version\":44,\"items\":[{\"productId\":10,\"quantity\":2},{\"productId\":20,\"quantity\":1}]}"));
        var restored = AssistantPurchaseScope.restore(mapper.valueToTree(original.snapshot()));
        assertEquals(0, restored.chargeable(10, 2));
        assertEquals(0, restored.chargeable(10, 1));
        assertEquals(2, restored.chargeable(20, 3));
        assertEquals(4, restored.chargeable(30, 4));
        assertEquals(2, restored.revise(30000, 2, false).chargeable(20, 3));
        assertEquals(3, restored.revise(30000, 2, true).chargeable(20, 3));
    }

    @Test void unavailableBaselineCannotBeConfusedWithEmptyCart() throws Exception {
        assertThrows(IllegalStateException.class, () -> AssistantPurchaseScope.capture(20000, 4, false, null));
        assertThrows(IllegalStateException.class, () -> AssistantPurchaseScope.capture(20000, 4, false, mapper.readTree("{}")));
    }

    @Test void quantityMustMatchCurrentUserQuote() throws Exception {
        assertEquals(4, AssistantPurchaseScope.quantity(mapper.readTree("{\"purchaseQuantity\":4,\"quantitySource\":\"四个\"}"), "预算200元买四个鼠标"));
        assertEquals(0, AssistantPurchaseScope.quantity(mapper.readTree("{\"purchaseQuantity\":8,\"quantitySource\":\"四个\"}"), "预算200元买四个鼠标"));
        assertEquals(0, AssistantPurchaseScope.quantity(mapper.readTree("{\"purchaseQuantity\":4,\"quantitySource\":\"四个\"}"), "预算200元推荐鼠标"));
        assertEquals(10000L, AssistantIntentRules.budget(List.of("预算100以内推荐鼠标")));
        assertNull(AssistantIntentRules.budget(List.of("预算100美元")));
    }
}
