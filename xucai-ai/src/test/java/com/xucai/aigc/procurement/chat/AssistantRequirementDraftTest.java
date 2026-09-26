package com.xucai.aigc.procurement.chat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssistantRequirementDraftTest {
    final ObjectMapper mapper=new ObjectMapper();
    ObjectNode input()throws Exception{return (ObjectNode)mapper.readTree("""
        {"title":"会议室采购","rawText":"两台摄像头，兼容性待核实；不需要无线功能。","budgetCents":100000,"usage":"会议室视频会议","items":[{"categoryId":5,"quantity":2}],"mustHave":["兼容性待核实","不需要无线功能"],"optional":[]}
        """);}
    @Test void keepsCustomerConditionsAndCalculatesTotal()throws Exception{
        var result=AssistantRequirementDraft.parse(input());var d=(Map<?,?>)result.get("structured");
        assertEquals(100000,d.get("budgetCents"));assertEquals(2,d.get("quantity"));assertEquals(List.of("兼容性待核实","不需要无线功能"),d.get("mustHave"));
    }
    @Test void unknownBudgetAndQuantitiesStayUnknown()throws Exception{
        var args=input();args.put("budgetCents",0);((ObjectNode)args.path("items").get(0)).put("quantity",0);
        var d=(Map<?,?>)AssistantRequirementDraft.parse(args).get("structured");assertNull(d.get("budgetCents"));assertNull(d.get("quantity"));assertNull(((Map<?,?>)((List<?>)d.get("items")).get(0)).get("quantity"));
    }
    @Test void rejectsDuplicateCategoriesAndExcessTotal()throws Exception{
        var args=input();args.withArray("items").add(args.path("items").get(0).deepCopy());assertThrows(RuntimeException.class,()->AssistantRequirementDraft.parse(args));
        var large=input();large.withArray("items").removeAll().addObject().put("categoryId",1).put("quantity",10000);
        large.withArray("items").addObject().put("categoryId",2).put("quantity",1);assertThrows(RuntimeException.class,()->AssistantRequirementDraft.parse(large));
    }
    @Test void rejectsMalformedTextAndOutOfRangeMoney()throws Exception{
        var args=input();args.put("rawText","x".repeat(4001));assertThrows(RuntimeException.class,()->AssistantRequirementDraft.parse(args));
        args.put("rawText","需求");args.put("budgetCents",100000001);assertThrows(RuntimeException.class,()->AssistantRequirementDraft.parse(args));
    }
}
