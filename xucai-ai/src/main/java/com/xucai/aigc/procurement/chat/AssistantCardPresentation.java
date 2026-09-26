package com.xucai.aigc.procurement.chat;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Only the server assigns actionable option numbers. Buffer incomplete lines before display. */
final class AssistantCardPresentation {
    private AssistantCardPresentation() { }

    private static final Pattern MODEL_NUMBERING = Pattern.compile(
            "(?:选项|选第|第)\\s*[一二三四五六七八九十0-9]+"
            + "|^\\s*(?:[-*#>]\\s*)*[（(]?[一二三四五六七八九十0-9]+[）).、：:]"
            + "|(?:前者|后者|上面那款|下面那款)", Pattern.MULTILINE);

    static boolean hasNumbering(String text) {
        return MODEL_NUMBERING.matcher(text.replace("*", "").replace("`", "")).find();
    }

    static String preview(List<Map<String,Object>> cards, String text, boolean catalogue) {
        String lines=text.substring(0,text.lastIndexOf('\n')+1);
        return catalogue||hasNumbering(lines)?render(cards,lines,false):lines.stripTrailing();
    }

    static String render(List<Map<String, Object>> cards, String text, boolean complete) {
        StringBuilder result = new StringBuilder("商品选项：\n");
        for (int i = 0; i < cards.size(); i++) {
            Map<String, Object> card = cards.get(i);
            String name = String.valueOf(card.get("name")).replaceAll("[\\r\\n]", " ");
            result.append("选项 ").append(i + 1).append("：").append(name);
            if (card.get("price") != null) {
                result.append(" · ¥").append(card.get("price"));
            }
            result.append('\n');
        }
        result.append("\n");
        // A provider chunk can stop halfway through “选项1”. Never release a partial line.
        String visible = complete ? text : text.substring(0, text.lastIndexOf('\n') + 1);
        for (String line : visible.split("\\R")) {
            if (!hasNumbering(line)) {
                result.append(line).append('\n');
            }
        }
        return result.toString().stripTrailing();
    }
}
