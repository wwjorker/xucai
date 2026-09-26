package com.xucai.aigc.procurement.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

public interface AssistantModel {
    boolean available();
    default String modelName() { return "未提供具体版本"; }
    JsonNode call(String turn,int round,String agent,List<Map<String,Object>> messages,List<Map<String,Object>> tools,boolean json);
    default JsonNode stream(String turn,int round,String agent,List<Map<String,Object>> messages,List<Map<String,Object>> tools,java.util.function.Consumer<String> text) {
        return call(turn,round,agent,messages,tools,false);
    }
    default void cancel(String turn) { }
}
