package com.xucai.aigc.procurement.chat;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssistantIntentRulesTest {
    @org.junit.jupiter.api.Test void currencyUpperBoundDoesNotRequireBudgetKeyword() {
        assertEquals(20000L, AssistantIntentRules.budget(java.util.List.of("给我推荐一款200元以内的键盘")));
        assertEquals(10000L, AssistantIntentRules.budget(java.util.List.of("预算200元，改到100元以下")));
        assertNull(AssistantIntentRules.budget(java.util.List.of("这款售价200元吗")));
    }
    @org.junit.jupiter.api.Test void ordinalReadReferenceDoesNotDecidePurchaseAndRejectsAmbiguity() {
        org.junit.jupiter.api.Assertions.assertEquals(2,AssistantIntentRules.referencedPosition("我想要了解一下第二款的参数",2));
        org.junit.jupiter.api.Assertions.assertEquals(2,AssistantIntentRules.referencedPosition("选项 2 的参数",2));
        org.junit.jupiter.api.Assertions.assertEquals(0,AssistantIntentRules.referencedPosition("第一款和第二款",2));
        org.junit.jupiter.api.Assertions.assertEquals(0,AssistantIntentRules.referencedPosition("第三款",2));
    }
    final List<Map<String,Object>> cards=List.of(Map.of("id","1","name","有线鼠标"),Map.of("id","2","name","无线鼠标"));
    @Test void initialCategoryPurchaseIsShoppingEvenWithOldCards(){
        assertTrue(AssistantIntentRules.categoryShopping("预算200元，买四个办公室用的有线鼠标。"));
        assertFalse(AssistantIntentRules.categoryShopping("请商家准备鼠标采购方案"));
    }
    @Test void modelCannotChooseOrChangeQuantityOnBehalfOfUser(){
        for(String text:List.of("预算200元我要买四个有线鼠标","第二款要2件","不要把第一款加入清单","第一款要2件吗","第一款要2件还是4件","如果便宜第一款要2件"))
            assertFalse(AssistantIntentRules.authorizes(text,cards,1,false,4),text);
        assertTrue(AssistantIntentRules.authorizes("第一款要2件",cards,1,false,2));
        assertFalse(AssistantIntentRules.authorizes("第一款要2件",cards,2,false,2));
        assertFalse(AssistantIntentRules.authorizes("第一款要2件",cards,1,true,2));
        assertFalse(AssistantIntentRules.authorizes("第一款要2件",List.of(),1,false,2));
        assertTrue(AssistantIntentRules.authorizes("第二款再加两件，然后查看我的订单",cards,2,true,2));
        assertTrue(AssistantIntentRules.authorizes("把第一款在我的采购清单里设为2件，不要下单。",cards,1,false,2));
    }
    @Test void budgetUsesExplicitYuanAndLatestValue(){
        assertEquals(20000L,AssistantIntentRules.budget(List.of("预算200元，买四个鼠标")));
        assertEquals(30050L,AssistantIntentRules.budget(List.of("预算200元","预算改了","预算300.50元")));
        assertNull(AssistantIntentRules.budget(List.of("鼠标单价200元")));
    }
    @Test void explicitSetAndIncreaseHaveDifferentServerCommands(){
        assertEquals(new AssistantIntentRules.Mutation(1,false,2),AssistantIntentRules.mutation("第一款要2件",cards));
        assertEquals(new AssistantIntentRules.Mutation(2,true,-2),AssistantIntentRules.mutation("第二款减两件",cards));
        assertNull(AssistantIntentRules.mutation("如果合适第一款要2件",cards));
        assertNull(AssistantIntentRules.mutation("不要把第一款设为2件",cards));
    }
    @Test void browsingCatalogDoesNotRequireBudgetButDoesNotOverrideOtherIntents(){
        assertEquals(1,AssistantIntentRules.catalogCategory("显示器"));
        assertEquals(2,AssistantIntentRules.catalogCategory("打印机"));
        assertEquals(0,AssistantIntentRules.catalogCategory("鼠标和键盘"));
        for(String text:List.of("有哪些有线鼠标？","你们有什么键盘","给我看看办公显示器","列一下在售打印机","请展示一下耳机列表"))
            assertTrue(AssistantIntentRules.catalogBrowsing(text),text);
        for(String text:List.of("想买鼠标","不要列出鼠标","这款鼠标有哪些接口","看看我的订单","你有哪些能力","如果有预算再看看鼠标"))
            assertFalse(AssistantIntentRules.catalogBrowsing(text),text);
    }
    @Test void explicitQuantityWithCartDestinationIsStillASetCommand(){
        for(String text:List.of("第二款要4件，放进清单，不要下单。",
                "请把第二款设为4件，然后加入采购清单，不用下单",
                "第二款买4件，放入我的清单")){
            assertEquals(new AssistantIntentRules.Mutation(2,false,4),AssistantIntentRules.mutation(text,cards),text);
            assertFalse(AssistantIntentRules.authorizes(text,cards,2,true,4),text);
        }
    }
    @Test void destinationSuffixDoesNotAuthorizeQuestionsNegationsOrCompoundChanges(){
        for(String text:List.of("第二款要4件？", "第二款要4件?",
                "第二款要4件，不要放进清单", "如果合适第二款要4件，放进清单",
                "第二款要4件还是2件，放进清单", "第二款要4件，第一款也要2件，放进清单",
                "第二款要4件，放进清单并下单", "第二款要4件，别加入清单")){
            assertNull(AssistantIntentRules.mutation(text,cards),text);
            assertFalse(AssistantIntentRules.authorizes(text,cards,2,false,4),text);
        }
    }
    @Test void purchaseVerbCanPrecedeOrdinalWithoutGuessingQuantity() {
        assertEquals(new AssistantIntentRules.Mutation(1,false,2), AssistantIntentRules.mutation("我想买选项1两件",cards));
        assertEquals(new AssistantIntentRules.Mutation(2,false,3), AssistantIntentRules.mutation("买第二款3个",cards));
        for (String text : List.of("不想买选项1两件", "我想买选项1两件吗？", "选项1我要买", "买你第一个推荐的"))
            assertNull(AssistantIntentRules.mutation(text,cards),text);
    }
}
