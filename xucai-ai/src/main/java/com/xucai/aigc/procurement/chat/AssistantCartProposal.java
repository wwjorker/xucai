package com.xucai.aigc.procurement.chat;

import com.fasterxml.jackson.databind.*;
import com.xucai.api.client.catalog.CandidateCatalogClient;
import com.xucai.api.client.trade.AssistantTradeClient;
import com.xucai.api.dto.mall.CandidateCatalogDTO.Product;
import java.util.*;

/** Semantic requests prepare an immutable, version-bound operation; only confirmation executes it. */
final class AssistantCartProposal {
    record Authorization(long productId, int quantity, long version) {
        boolean matches(long id, boolean relative, int amount) {
            return !relative && productId == id && quantity == amount;
        }
    }
    private final CandidateCatalogClient catalog;
    private final AssistantTradeClient trade;
    private final ObjectMapper mapper;
    private final AssistantTools tools;

    AssistantCartProposal(CandidateCatalogClient catalog, AssistantTradeClient trade, ObjectMapper mapper, AssistantTools tools) {
        this.catalog = catalog; this.trade = trade; this.mapper = mapper; this.tools = tools;
    }

    static JsonNode commandReply(AssistantTools.Context ctx, ObjectMapper mapper) {
        // These are the exact control replies advertised by the pending-operation UI.
        // Mixed sentences still require semantic interpretation and cannot execute here.
        String action = switch (ctx.requestText.strip()) {
            case "确认" -> "CONFIRM";
            case "取消" -> "REJECT";
            default -> null;
        };
        return action == null ? quantityReply(ctx, mapper)
                : mapper.createObjectNode().put("action", action).put("source", ctx.requestText);
    }

    static JsonNode quantityReply(AssistantTools.Context ctx, ObjectMapper mapper) {
        if (!ctx.cartProposal.containsKey("productId")) return null;
        var match = java.util.regex.Pattern.compile("(([0-9]{1,3}|[零一二两三四五六七八九十]{1,3})\\s*(?:件|个|台|副|只))\\s*[。.!！]?").matcher(ctx.requestText.strip());
        if (!match.matches()) return null;
        int count = AssistantIntentRules.number(match.group(2));
        if (count < 1 || count > 999) return null;
        return mapper.createObjectNode().put("action", "REVISE").put("source", ctx.requestText)
                .put("purchaseQuantity", count).put("quantitySource", match.group(1));
    }

    boolean handle(JsonNode request, AssistantTools.Context ctx) {
        String action = request.path("action").asText("NONE");
        if (!Set.of("NONE", "SET", "ADD", "REMOVE", "CLEAR", "CONFIRM", "REJECT", "REVISE").contains(action)) return false;
        // The model interprets the whole current message, not a quoted historical command.
        if (!request.path("source").asText().strip().equals(ctx.requestText.strip())) return false;
        int quotedCount = AssistantPurchaseScope.quantity(request, ctx.requestText);
        String quantityMode = action.equals("ADD") ? "ADD" : "SET";
        // Validate arithmetic from the quoted quantity phrase, after semantic intent selection.
        // This never selects a product, grants write permission or classifies a question as buying.
        String explicitMode = null;
        if (quotedCount > 0) {
            String text = ctx.requestText.replaceAll("\\s+", "");
            String quantitySource = request.path("quantitySource").asText().replaceAll("\\s+", "");
            if (!quantitySource.isEmpty()) {
                for (String operator : List.of("再加", "再来", "多要"))
                    if (text.contains(operator + quantitySource)) explicitMode = "ADD";
                for (String operator : List.of("改成", "改为", "设为", "总共要", "一共要"))
                    if (text.contains(operator + quantitySource)) explicitMode = "SET";
            }
        }
        if (explicitMode != null) quantityMode = explicitMode;
        String targetName = request.path("name").asText("").strip().toLowerCase(Locale.ROOT);
        boolean newTarget = !targetName.isBlank() && ctx.requestText.toLowerCase(Locale.ROOT).contains(targetName)
                || AssistantIntentRules.referencedPosition(ctx.requestText, ctx.cards.size()) > 0
                || !modelCodes(ctx.requestText).isEmpty();
        boolean bareQuantity = ctx.requestText.strip().equals(request.path("quantitySource").asText().strip());
        if (ctx.cartProposal.containsKey("productId") && quotedCount > 0 && !newTarget
                && (action.equals("SET") || action.equals("ADD") || action.equals("NONE") && bareQuantity)) {
            // A quantity answer edits the saved target even when the model calls it a new SET.
            // This only prepares a revision; it never performs a write.
            action = "REVISE";
            var revisedRequest = request.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) revisedRequest).put("name", "");
            request = revisedRequest;
        }
        if (action.equals("NONE")) return false;
        if (action.equals("REJECT")) {
            boolean existed = !ctx.cartProposal.isEmpty();
            ctx.cartProposal = Map.of();
            ctx.clarification = existed ? "已取消这项待确认操作，采购清单没有改变。" : "好的，目前没有待取消的清单操作，清单没有改变。";
            return true;
        }
        if (action.equals("CONFIRM")) {
            var proposed = mapper.valueToTree(ctx.cartProposal);
            var mentionedCodes = modelCodes(ctx.requestText);
            int mentionedPosition = AssistantIntentRules.referencedPosition(ctx.requestText, 8);
            boolean mentionedName = !targetName.isBlank() && ctx.requestText.toLowerCase(Locale.ROOT).contains(targetName);
            boolean differentTarget = proposed.has("operation") && (
                    mentionedCodes.stream().anyMatch(code -> !matchesCode(proposed.path("name").asText(), code))
                    || mentionedName && !proposed.path("name").asText().toLowerCase(Locale.ROOT).contains(targetName)
                    || mentionedPosition > 0 && (mentionedPosition > ctx.cards.size()
                        || !String.valueOf(ctx.cards.get(mentionedPosition - 1).get("id")).equals(proposed.path("productId").asText())));
            if (differentTarget) {
                ctx.cartProposal = Map.of();
                ctx.clarification = "你这次提到的商品与待办不一致，原待办已取消。请重新说明商品和数量；清单没有改变。";
                return true;
            }
            int revised = AssistantPurchaseScope.quantity(request, ctx.requestText);
            var pending = mapper.valueToTree(ctx.cartProposal);
            if (revised > 0 && pending.has("quantity") && revised != pending.path("requestedCount").asInt(pending.path("quantity").asInt())) action = "REVISE";
            else { confirm(ctx); return true; }
        }
        var cart = trade.cart(ctx.user, 2L);
        if (cart == null || !cart.has("version") || !cart.path("items").isArray())
            throw new IllegalStateException("Cart unavailable");
        if (action.equals("CLEAR")) {
            ctx.cards = List.of(); ctx.cardReferencesTrusted = false;
            if (cart.path("items").isEmpty()) {
                ctx.cartProposal = Map.of(); ctx.clarification = "当前采购清单已经为空，无需清空。";
            } else {
                ctx.cartProposal = Map.of("operation", "CLEAR", "version", cart.path("version").asLong(),
                        "createdAt", System.currentTimeMillis(), "lines", cart.path("items").size());
                ctx.clarification = "将清空采购清单中的 " + cart.path("items").size() + " 款商品。回复“确认”执行，或回复“取消”；目前尚未修改。";
            }
            return true;
        }
        Product product;
        if (action.equals("REVISE")) {
            var pending = mapper.valueToTree(ctx.cartProposal);
            if (!pending.has("productId")) {
                product = resolve(request, true, cart, ctx);
                boolean explicitTarget = !modelCodes(ctx.requestText).isEmpty() || newTarget;
                if (product == null && !explicitTarget && ctx.lastCartProductId > 0) {
                    for (var row : cart.path("items")) if (row.path("productId").asLong() == ctx.lastCartProductId)
                        product = catalog.product(ctx.lastCartProductId, ctx.user, 2L);
                }
                if (product == null) {
                    ctx.clarification = "请说明要修改清单中的哪款商品和最终件数；本次没有修改。";
                    return true;
                }
                action = "SET";
            } else {
                if (stale(pending, cart)) {
                    ctx.cartProposal = Map.of(); ctx.clarification = "没有可修改的有效待确认商品，请重新说明商品和数量。"; return true;
                }
                String replacement = request.path("name").asText("").strip().toLowerCase(Locale.ROOT);
                if (!replacement.isBlank() && !pending.path("name").asText().toLowerCase(Locale.ROOT).contains(replacement)) {
                    ctx.cartProposal = Map.of(); ctx.clarification = "你改变了商品，请重新说明新商品和数量；原待办已取消，清单没有改变。"; return true;
                }
                product = catalog.product(pending.path("productId").asLong(), ctx.user, 2L);
                if (explicitMode == null && "ADD".equals(pending.path("quantityMode").asText())) quantityMode = "ADD";
            }
        } else product = resolve(request, action.equals("REMOVE"), cart, ctx);
        if (product == null) {
            ctx.cartProposal = Map.of();
            if ((action.equals("SET") || action.equals("ADD")) && modelCodes(ctx.requestText).isEmpty()
                    && AssistantIntentRules.referencedPosition(ctx.requestText, 8) == 0) {
                ctx.needsProductSelection = true;
                return false;
            }
            ctx.clarification = action.equals("REMOVE")
                    ? "未能在你的清单中唯一确定这款商品，请说明完整名称；清单没有改变。"
                    : "还不能唯一确定商品，请说明商品型号或当前卡片的选项编号；清单没有改变。";
            return true;
        }
        int previous = 0;
        for (var row : cart.path("items")) if (row.path("productId").asLong() == product.id()) previous = row.path("quantity").asInt();
        int count = action.equals("REMOVE") ? 0 : AssistantPurchaseScope.quantity(request, ctx.requestText);
        int quantity = !action.equals("REMOVE") && count == 0 ? -1 : !action.equals("REMOVE") && quantityMode.equals("ADD") ? previous + count : count;
        if (quantity > 999) { ctx.clarification = "调整后的数量不能超过999件，请重新说明。"; ctx.cartProposal = Map.of(); return true; }
        if (quantity > 0 && (!"PUBLISHED".equals(product.status()) || product.sale() == null || product.sale().price() == null)) {
            ctx.cartProposal = Map.of(); ctx.clarification = "这款商品的当前售价或在售状态无法确认，请在商品页核对。"; return true;
        }
        var pending = new LinkedHashMap<String, Object>();
        pending.put("operation", "SET"); pending.put("version", cart.path("version").asLong());
        pending.put("createdAt", System.currentTimeMillis()); pending.put("productId", product.id().toString());
        pending.put("name", product.name()); pending.put("quantity", quantity); pending.put("previous", previous);
        pending.put("quantityMode", action.equals("REMOVE") ? "SET" : quantityMode); pending.put("requestedCount", count);
        pending.put("priceCents", product.sale() == null ? null : product.sale().price());
        ctx.cartProposal = pending;
        selectCard(product, ctx);
        if (quantity < 0) {
            ctx.clarification = "你需要“" + product.name() + "”多少件？目前尚未修改清单。";
            return true;
        }
        ctx.clarification = "准备将“" + product.name() + "”从 " + previous + " 件改为 " + quantity
                + " 件" + (quantity == 0 ? "（从清单移除）" : "")
                + "。回复“确认”执行，或告诉我新的数量、回复“取消”。目前尚未修改清单，也未下单。";
        return true;
    }

    private Product resolve(JsonNode request, boolean fromCart, JsonNode cart, AssistantTools.Context ctx) {
        String keyword = request.path("name").asText("").strip().toLowerCase(Locale.ROOT);
        int position = request.path("position").asInt(0);
        if (!fromCart && position > 0 && ctx.cardReferencesTrusted
                && position == AssistantIntentRules.referencedPosition(ctx.requestText, ctx.cards.size()))
            return catalog.product(Long.parseLong(ctx.cards.get(position - 1).get("id").toString()), ctx.user, 2L);
        var codes = modelCodes(ctx.requestText);
        boolean groundedName = keyword.length() >= 2 && keyword.length() <= 80
                && ctx.requestText.toLowerCase(Locale.ROOT).contains(keyword);
        if (codes.isEmpty() && !groundedName) return null;
        List<Product> pool = new ArrayList<>();
        if (fromCart) {
            for (var row : cart.path("items")) pool.add(catalog.product(row.path("productId").asLong(), ctx.user, 2L));
        } else pool.addAll(catalog.list(null, 1, 100, ctx.user, 2L).items());
        var matches = pool.stream().filter(Objects::nonNull)
                .filter(p -> fromCart || "PUBLISHED".equals(p.status()))
                .filter(p -> codes.isEmpty() ? (fromCart ? p.name().toLowerCase(Locale.ROOT).contains(keyword)
                        : ctx.requestText.toLowerCase(Locale.ROOT).contains(p.name().toLowerCase(Locale.ROOT)))
                        : codes.stream().anyMatch(code -> matchesCode(p.name(), code))).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static Set<String> modelCodes(String text) {
        var codes = new LinkedHashSet<String>();
        var tokens = java.util.regex.Pattern.compile("[A-Za-z0-9]+(?:[-.][A-Za-z0-9]+)*").matcher(text);
        while (tokens.find()) {
            String token = tokens.group();
            if (token.matches(".*[A-Za-z].*") && token.matches(".*[0-9].*")) codes.add(token.toLowerCase(Locale.ROOT));
        }
        return codes;
    }

    private static boolean matchesCode(String name, String code) {
        return java.util.regex.Pattern.compile("(?<![a-z0-9])" + java.util.regex.Pattern.quote(code) + "(?![a-z0-9])")
                .matcher(name.toLowerCase(Locale.ROOT)).find();
    }

    private void selectCard(Product product, AssistantTools.Context ctx) {
        var card = new LinkedHashMap<String, Object>();
        card.put("id", product.id().toString()); card.put("name", product.name()); card.put("category", product.categoryId());
        card.put("price", product.sale() == null || product.sale().price() == null ? null : product.sale().price() / 100.0);
        ctx.cards = List.of(card); ctx.cardReferencesTrusted = true;
    }

    private boolean stale(JsonNode pending, JsonNode cart) {
        long age = System.currentTimeMillis() - pending.path("createdAt").asLong();
        return age < 0 || age > 15 * 60_000L || !pending.has("version")
                || pending.path("version").asLong() != cart.path("version").asLong();
    }

    private void confirm(AssistantTools.Context ctx) {
        var pending = mapper.valueToTree(ctx.cartProposal);
        ctx.cartProposal = Map.of(); // Persist a tombstone: later confirmations cannot replay it.
        if (!pending.has("operation")) { ctx.clarification = "目前没有待确认的清单操作，请先说明要调整哪款商品。"; return; }
        var cart = trade.cart(ctx.user, 2L);
        if (cart == null || !cart.has("version") || stale(pending, cart)) {
            ctx.clarification = "清单已发生变化或这项操作已过期，请重新核对商品和数量；本次没有修改。"; return;
        }
        if (pending.path("operation").asText().equals("CLEAR")) {
            trade.saveCart(Map.of("expectedVersion", cart.path("version").asLong(), "items", List.of()), ctx.user, 2L);
            ctx.lastCartProductId = 0;
            ctx.cartChanged = true; ctx.cartChanges.add("已清空采购清单"); return;
        }
        long id = pending.path("productId").asLong();
        int quantity = pending.path("quantity").asInt(-1);
        if (quantity < 0) {
            ctx.cartProposal = mapper.convertValue(pending, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
            ctx.clarification = "还需要说明“" + pending.path("name").asText() + "”的件数；目前没有修改清单。";
            return;
        }
        var product = catalog.product(id, ctx.user, 2L);
        if (quantity < 0 || quantity > 999 || product == null || quantity > 0
                && (product.sale() == null || product.sale().price() == null
                    || product.sale().price() != pending.path("priceCents").asLong())) {
            ctx.clarification = "商品价格或状态已变化，请重新核对后再确认；本次没有修改。"; return;
        }
        selectCard(product, ctx);
        ctx.confirmedCartAction = new Authorization(id, quantity, cart.path("version").asLong());
        try {
            tools.execute("PROCUREMENT", "set_cart_quantity", mapper.createObjectNode().put("position", 1).put("quantity", quantity), ctx);
        } finally { ctx.confirmedCartAction = null; }
    }
}
