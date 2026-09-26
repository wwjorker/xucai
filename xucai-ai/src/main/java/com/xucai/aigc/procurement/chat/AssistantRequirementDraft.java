package com.xucai.aigc.procurement.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.xucai.common.exceptions.BusinessException;
import java.util.*;

/** Editable proposal only. This never writes a procurement requirement. */
final class AssistantRequirementDraft {
    private AssistantRequirementDraft() {}
    static String text(JsonNode n,String key,int max,boolean required){
        var value=n.get(key);
        if(value==null||!value.isTextual())throw invalid();
        String s=value.asText().strip();if(s.length()>max||(required&&s.isEmpty()))throw invalid();return s;
    }
    static BusinessException invalid(){return new BusinessException(400,"需求草稿字段不完整或超出范围，请简化条件后再整理");}
    static List<String> lines(JsonNode args,String key){
        var a=args.get(key);if(a==null||!a.isArray()||a.size()>20)throw invalid();
        List<String> result=new ArrayList<>();for(var v:a){if(!v.isTextual()||v.asText().isBlank()||v.asText().length()>200)throw invalid();result.add(v.asText().strip());}return result;
    }
    static Map<String,Object> parse(JsonNode args){
        String title=text(args,"title",100,true),rawText=text(args,"rawText",4000,true),usage=text(args,"usage",1000,false);
        int budget=AssistantTools.integer(args,"budgetCents",0,100000000);
        var input=args.get("items");if(input==null||!input.isArray()||input.size()>6)throw invalid();
        List<Map<String,Object>> items=new ArrayList<>();Set<Integer> categories=new LinkedHashSet<>();int total=0;boolean known=!input.isEmpty();
        for(var row:input){int category=AssistantTools.integer(row,"categoryId",1,6),quantity=AssistantTools.integer(row,"quantity",0,10000);
            if(!categories.add(category))throw invalid();total+=quantity;if(total>10000)throw invalid();known&=quantity>0;
            var item=new LinkedHashMap<String,Object>();item.put("categoryId",category);item.put("quantity",quantity==0?null:quantity);items.add(item);}
        var structured=new LinkedHashMap<String,Object>();structured.put("budgetCents",budget==0?null:budget);structured.put("quantity",known?total:null);
        structured.put("categoryIds",new ArrayList<>(categories));structured.put("items",items);structured.put("usage",usage);
        structured.put("mustHave",lines(args,"mustHave"));structured.put("optional",lines(args,"optional"));
        return Map.of("title",title,"rawText",rawText,"structured",structured);
    }
}
