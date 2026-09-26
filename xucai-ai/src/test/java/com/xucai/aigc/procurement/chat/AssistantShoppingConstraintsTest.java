package com.xucai.aigc.procurement.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.CandidateCatalogDTO.Product;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssistantShoppingConstraintsTest {
    final ObjectMapper mapper = new ObjectMapper();
    final AssistantShoppingConstraints constraints = new AssistantShoppingConstraints();

    Product product(int category, String specs) throws Exception {
        return new Product(1L, "设备", category, 1L, mapper.readTree(specs), "PUBLISHED", true, null, null);
    }

    @Test void budgetOnlyTurnKeepsWiredAcrossRestoration() throws Exception {
        constraints.update(mapper.readTree("{\"category\":\"鼠标\",\"connection\":\"WIRED\",\"source\":\"不要无线\"}"), "鼠标不要无线");
        var next = new AssistantShoppingConstraints();
        next.restore(mapper.valueToTree(constraints.snapshot()));
        next.update(mapper.readTree("{\"connection\":\"KEEP\"}"), "预算2000元，办公室用，两只");
        assertTrue(next.accepts(product(3, "{\"connection\":\"USB 有线\"}")));
        assertFalse(next.accepts(product(3, "{\"connection\":\"USB无线接收器\"}")));
    }

    @Test void unknownNegativeAndUsbReceiverAreNotWiredEvidence() throws Exception {
        constraints.restore(mapper.readTree("{\"3\":\"WIRED\"}"));
        for (String value : new String[]{"USB", "USB无线接收器", "不支持有线", "wired unknown", "无线", "wireless"})
            assertFalse(constraints.accepts(product(3, mapper.createObjectNode().put("connection", value).toString())), value);
        assertFalse(constraints.accepts(product(3, "{}")));
        assertTrue(constraints.accepts(product(3, "{\"connections\":[\"Bluetooth\",\"USB wired\"]}")));
    }

    @Test void categoryIsolationAndExplicitRelease() throws Exception {
        constraints.restore(mapper.readTree("{\"3\":\"WIRED\"}"));
        assertTrue(constraints.accepts(product(4, "{\"connection\":\"无线\"}")));
        constraints.update(mapper.readTree("{\"category\":\"鼠标\",\"connection\":\"ANY\",\"source\":\"无线也可以\"}"), "无线也可以");
        assertTrue(constraints.accepts(product(3, "{\"connection\":\"无线\"}")));
        assertTrue(constraints.snapshot().isEmpty());
    }

    @Test void modelCannotInventReleaseSourceOrUnsupportedFacet() throws Exception {
        constraints.restore(mapper.readTree("{\"3\":\"WIRED\"}"));
        assertThrows(RuntimeException.class, () -> constraints.update(mapper.readTree("{\"category\":\"鼠标\",\"connection\":\"ANY\",\"source\":\"无线也可以\"}"), "预算2000元"));
        assertThrows(RuntimeException.class, () -> constraints.update(mapper.readTree("{\"category\":\"鼠标\",\"connection\":\"FAST\",\"source\":\"快\"}"), "快"));
        assertEquals("WIRED", constraints.snapshot().get("3"));
    }

    @Test void bluetoothDiffersFromGenericWireless() throws Exception {
        constraints.restore(mapper.readTree("{\"3\":\"BLUETOOTH\"}"));
        assertFalse(constraints.accepts(product(3, "{\"connection\":\"USB无线接收器\"}")));
        assertTrue(constraints.accepts(product(3, "{\"connections\":[\"Bluetooth\",\"Unifying USB接收器\"]}")));
    }
    @Test void restatingExistingValueWithoutNewQuoteDoesNotRejectBudgetSupplement() throws Exception {
        constraints.restore(mapper.readTree("{\"3\":\"WIRED\"}"));
        constraints.update(mapper.readTree("{\"category\":\"鼠标\",\"connection\":\"WIRED\",\"source\":\"有线鼠标\"}"), "预算2000元");
        assertEquals("WIRED", constraints.snapshot().get("3"));
        assertEquals("WIRED", constraints.promptSnapshot().get("鼠标"));
    }
    @Test void absentProtocolFieldCannotSilentlyDisableFiltering() throws Exception {
        constraints.restore(mapper.readTree("{\"3\":\"WIRED\"}"));
        for(var node:java.util.List.of(mapper.missingNode(),mapper.nullNode(),mapper.createObjectNode(),mapper.getNodeFactory().textNode("KEEP")))
            assertThrows(com.xucai.aigc.procurement.ModelCallException.class,()->constraints.update(node,"预算200元"));
        assertEquals("WIRED",constraints.snapshot().get("3"));
    }
}
