package com.xucai.aigc.procurement.chat;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AssistantCardPresentationTest {
    @Test void ordinaryConversationDoesNotRepeatAnOldCatalogue(){
        assertEquals("我是序采助手。",AssistantCardPresentation.preview(cards,"我是序采助手。\n",false));
        assertEquals("",AssistantCardPresentation.preview(cards,"选项1",false));
        assertFalse(AssistantCardPresentation.preview(cards,"选项1：M100\n",false).contains("选项1：M100"));
    }
    private final List<Map<String,Object>> cards=List.of(
        Map.of("id","36","name","罗技 M720","price",299),
        Map.of("id","37","name","罗技 M100","price",39));

    @Test void reorderedModelOptionsCannotBecomeActionableNumbers(){
        for(String bad:List.of("**选项1：M100**", "1. M100", "一、M100", "第一款是M100", "前者M100")){
            String reply=AssistantCardPresentation.render(cards,bad+"\nM100适合有线办公。",true);
            assertTrue(reply.contains("选项 1：罗技 M720"));
            assertTrue(reply.contains("选项 2：罗技 M100"));
            assertFalse(reply.contains(bad),bad);
            assertTrue(reply.contains("M100适合有线办公"));
        }
    }
    @Test void everyProviderSplitWithholdsAnUnverifiedNumberingLine(){
        String bad="**选项1：M100**\n";
        for(int i=0;i<=bad.length();i++){
            String reply=AssistantCardPresentation.render(cards,bad.substring(0,i),false);
            assertFalse(reply.contains("选项1"));
            assertFalse(reply.contains("：M100"));
        }
    }
    @Test void legitimateNumbersAndCompletedLinesRemainVisible(){
        String text="M100每件39元，4件156元。\nM720支持3台设备。";
        String partial=AssistantCardPresentation.render(cards,text,false);
        assertTrue(partial.contains("4件156元"));
        assertFalse(partial.contains("支持3台设备"));
        assertTrue(AssistantCardPresentation.render(cards,text,true).contains("支持3台设备"));
    }
}
