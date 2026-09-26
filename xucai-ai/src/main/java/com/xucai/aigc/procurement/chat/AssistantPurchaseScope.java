package com.xucai.aigc.procurement.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** A saved budget applies to additions since its cart snapshot, unless explicitly cart-wide. */
final class AssistantPurchaseScope {
    final long budget;
    final int quantity;
    final boolean wholeCart;
    private final Map<String, Integer> baseline;

    AssistantPurchaseScope(long budget, int quantity, boolean wholeCart, Map<String, Integer> baseline) {
        if (budget <= 0 || quantity < 0 || quantity > 999) throw new IllegalArgumentException("Invalid purchase scope");
        this.budget = budget;
        this.quantity = quantity;
        this.wholeCart = wholeCart;
        this.baseline = Map.copyOf(baseline);
    }

    AssistantPurchaseScope revise(long budget, int quantity, boolean wholeCart) {
        return new AssistantPurchaseScope(budget, quantity, wholeCart, baseline);
    }

    int chargeable(long productId, int currentQuantity) {
        return wholeCart ? currentQuantity : Math.max(0, currentQuantity - baseline.getOrDefault(Long.toString(productId), 0));
    }

    Map<String, Object> snapshot() {
        return Map.of("version", 1, "budgetCents", budget, "quantity", quantity,
                "wholeCart", wholeCart, "baseline", baseline);
    }

    static AssistantPurchaseScope restore(JsonNode node) {
        if (node == null || node.path("version").asInt() != 1) return null;
        var baseline = new LinkedHashMap<String, Integer>();
        node.path("baseline").fields().forEachRemaining(e -> baseline.put(e.getKey(), e.getValue().asInt()));
        return new AssistantPurchaseScope(node.path("budgetCents").asLong(), node.path("quantity").asInt(),
                node.path("wholeCart").asBoolean(), baseline);
    }

    static AssistantPurchaseScope capture(long budget, int quantity, boolean wholeCart, JsonNode cart) {
        if (cart == null || !cart.path("items").isArray() || !cart.has("version"))
            throw new IllegalStateException("Cannot establish budget without current cart");
        var baseline = new LinkedHashMap<String, Integer>();
        for (var row : cart.path("items")) {
            long id = row.path("productId").asLong();
            int count = row.path("quantity").asInt();
            if (id <= 0 || count <= 0) throw new IllegalStateException("Invalid cart baseline");
            baseline.put(Long.toString(id), count);
        }
        return new AssistantPurchaseScope(budget, quantity, wholeCart, baseline);
    }

    /** Validate the router's quantity against a quoted numeric span, never a guessed default. */
    static int quantity(JsonNode route, String text) {
        String source = route.path("quantitySource").asText("");
        int value = route.path("purchaseQuantity").asInt(0);
        if (value < 1 || value > 999 || source.isBlank() || !text.contains(source)) return 0;
        var matcher = java.util.regex.Pattern.compile("([0-9]{1,3}|[零一二两三四五六七八九十]{1,3})\\s*(?:件|个|台|副|只)").matcher(source);
        return matcher.matches() && AssistantIntentRules.number(matcher.group(1)) == value ? value : 0;
    }
}
