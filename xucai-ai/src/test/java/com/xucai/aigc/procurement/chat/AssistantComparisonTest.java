package com.xucai.aigc.procurement.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.CandidateCatalogDTO.Product;
import com.xucai.api.dto.mall.SaleItemDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssistantComparisonTest {
    @Test void keepsFalseUnknownPriceUnitsAndExactCompatibilityDistinct() throws Exception {
        var mapper=new ObjectMapper();
        var a=new Product(1L,"甲",3,1L,mapper.readTree("{\"plug_and_play\":false,\"receiver_protocol\":\"non-Unifying\",\"source_region\":\"US\"}"),"PUBLISHED",true,new SaleItemDTO(1L,3900L,10,true,1L),null);
        var b=new Product(2L,"乙",3,1L,mapper.readTree("{\"connection\":\"USB 无线接收器\"}"),"PUBLISHED",true,null,null);
        String text=AssistantComparison.render(List.of(a,b));
        assertTrue(text.contains("甲：¥39.00 / 件"));
        assertTrue(text.contains("甲：不支持"));assertTrue(text.contains("乙：资料未提供"));
        assertTrue(text.contains("甲：non-Unifying"));assertTrue(text.contains("乙：USB 无线接收器"));
        assertFalse(text.contains("通常"));assertFalse(text.contains("零延迟"));
        assertFalse(text.contains("资料地区"));
    }
}
