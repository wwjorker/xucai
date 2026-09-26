package com.xucai.aigc.procurement.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.xucai.aigc.procurement.ModelCallException;
import com.xucai.api.dto.mall.CandidateCatalogDTO.Product;

import java.util.*;

/** Persisted, category-scoped connection requirements. The model proposes changes; tools enforce them. */
final class AssistantShoppingConstraints {
    private final Map<Integer, String> connections = new TreeMap<>();

    void restore(JsonNode saved) {
        connections.clear();
        if (!saved.isObject()) return;
        saved.fields()
                .forEachRemaining(
                        e -> {
                            int category;
                            try {
                                category = Integer.parseInt(e.getKey());
                            } catch (NumberFormatException ignored) {
                                return;
                            }
                            if (category >= 1
                                    && category <= 6
                                    && Set.of("WIRED", "WIRELESS", "BLUETOOTH")
                                            .contains(e.getValue().asText())) {
                                connections.put(category, e.getValue().asText());
                            }
                        });
    }

    void update(JsonNode change, String request) {
        if (!change.isObject() || !change.path("connection").isTextual())
            throw new ModelCallException("SHOPPING_CONSTRAINT", "本次未完整识别选购条件，请重新描述连接方式；没有修改采购清单。");
        String mode = change.path("connection").asText();
        if ("KEEP".equals(mode)) return;
        int category = category(change.path("category").asText());
        String source = change.path("source").asText();
        // Repeating an existing value is not a change and needs no new source quotation.
        if (category != 0 && mode.equals(connections.get(category))) return;
        if (!change.isObject()
                || category == 0
                || !Set.of("ANY", "WIRED", "WIRELESS", "BLUETOOTH").contains(mode)
                || source.isBlank()
                || source.length() > 500
                || !request.contains(source)) {
            throw new ModelCallException(
                    "SHOPPING_CONSTRAINT", "这次没有确定要如何调整选购条件，请说明连接方式是否需要改变；清单没有修改。");
        }
        if ("ANY".equals(mode)) connections.remove(category);
        else connections.put(category, mode);
    }

    static int category(String name) {
        return switch (name) {
            case "显示器" -> 1;
            case "打印机" -> 2;
            case "鼠标" -> 3;
            case "键盘" -> 4;
            case "摄像头" -> 5;
            case "耳机" -> 6;
            default -> 0;
        };
    }

    Map<String, String> snapshot() {
        var result = new LinkedHashMap<String, String>();
        connections.forEach((category, mode) -> result.put(category.toString(), mode));
        return result;
    }

    Map<String, String> promptSnapshot() {
        var names = List.of("", "显示器", "打印机", "鼠标", "键盘", "摄像头", "耳机");
        var result = new LinkedHashMap<String, String>();
        connections.forEach((category, mode) -> result.put(names.get(category), mode));
        return result;
    }

    boolean accepts(Product product) {
        if (product == null) return connections.isEmpty();
        String required = connections.get(product.categoryId());
        if (required == null) return true;
        JsonNode spec = product.specJson();
        if (spec == null) return false;
        var values = new ArrayList<String>();
        for (String field : List.of("connection", "connections")) {
            JsonNode node = spec.path(field);
            if (node.isTextual()) values.add(node.asText());
            else if (node.isArray())
                node.forEach(
                        value -> {
                            if (value.isTextual()) values.add(value.asText());
                        });
        }
        return values.stream().anyMatch(value -> confirmed(value, required));
    }

    private static boolean confirmed(String value, String required) {
        String text = value.toLowerCase(Locale.ROOT);
        // Unknown or negative statements are not positive capabilities. USB alone may mean a radio
        // receiver.
        if (text.matches(".*(不支持|不具备|不能|未知|待核|未确认|未证实|not |no |unsupported|unknown).*"))
            return false;
        boolean bluetooth = text.contains("蓝牙") || text.contains("bluetooth");
        boolean wireless =
                bluetooth
                        || text.contains("无线")
                        || text.contains("wireless")
                        || text.contains("unifying")
                        || text.contains("优联");
        return switch (required) {
            case "WIRED" -> text.contains("有线") || text.matches(".*\\bwired\\b.*");
            case "WIRELESS" -> wireless;
            case "BLUETOOTH" -> bluetooth;
            default -> false;
        };
    }
}
