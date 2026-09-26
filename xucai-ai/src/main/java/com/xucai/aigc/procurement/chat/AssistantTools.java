package com.xucai.aigc.procurement.chat;

import com.fasterxml.jackson.databind.*;
import com.xucai.aigc.procurement.retrieval.AssistantEvidenceSearch;
import com.xucai.api.client.catalog.CandidateCatalogClient;
import com.xucai.api.client.trade.AssistantTradeClient;
import com.xucai.api.dto.mall.CandidateCatalogDTO.Product;
import com.xucai.common.exceptions.BusinessException;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@Profile("xucai")
public class AssistantTools {
    private final CandidateCatalogClient catalog;
    private final AssistantTradeClient trade;
    private final ObjectMapper mapper;
    private final AssistantEvidenceSearch evidenceSearch;
    private final AssistantToolCatalog toolCatalog;

    public AssistantTools(
            CandidateCatalogClient catalog,
            AssistantTradeClient trade,
            ObjectMapper mapper,
            AssistantEvidenceSearch evidenceSearch) {
        this.catalog = catalog;
        this.trade = trade;
        this.mapper = mapper;
        this.evidenceSearch = evidenceSearch;
        this.toolCatalog = new AssistantToolCatalog(this, mapper);
    }

    public static class Context {
        public final long user;
        public List<Map<String, Object>> cards;
        public final List<Object> evidence = new ArrayList<>();
        public boolean cartChanged = false, checkout = false, ordersShown = false;
        public boolean cardReferencesTrusted = false;
        public boolean describingCart = false;
        public String orderSummary = "", cartSummary = "";
        public String clarification = "";
        public String comparisonText = "";
        public String requestText = "";
        public Long budgetCents;
        AssistantPurchaseScope purchaseScope;
        Map<String, Object> cartProposal = Map.of();
        AssistantCartProposal.Authorization confirmedCartAction;
        boolean needsProductSelection;
        long lastCartProductId;
        public Map<String, Object> shoppingCartSnapshot = Map.of();
        public final AssistantShoppingConstraints shoppingConstraints =
                new AssistantShoppingConstraints();
        public final List<Map<String, Object>> originalCards;
        public final List<String> cartChanges = new ArrayList<>();
        private final Map<Long, String> mutationRequests = new HashMap<>();
        private final Map<Long, Object> mutationResults = new HashMap<>();
        public Map<String, Object> requirementDraft;
        public List<String> requirementSources = List.of();
        public String turnId;
        public final List<Map<String, Object>> retrievals = new ArrayList<>();
        public final List<Map<String, Object>> catalogSearches = new ArrayList<>();
        private final Map<String, AssistantEvidenceSearch.Result> evidenceCache = new HashMap<>();

        public Context(long user, List<Map<String, Object>> cards) {
            this.user = user;
            this.cards = new ArrayList<>(cards);
            this.originalCards = List.copyOf(cards);
        }
    }

    static final Map<String, Set<String>> ALLOWED =
            Map.of(
                    "GENERAL", Set.of(),
                    "SHOPPING", Set.of("search_products", "read_product", "compare_products"),
                    "KNOWLEDGE",
                            Set.of(
                                    "search_products",
                                    "read_product",
                                    "read_evidence",
                                    "compare_products",
                                    "ask_clarification"),
                    "PROCUREMENT",
                            Set.of(
                                    "read_cart",
                                    "set_cart_quantity",
                                    "adjust_cart_quantity",
                                    "ask_clarification",
                                    "list_orders",
                                    "open_checkout",
                                    "prepare_requirement"));

    public boolean handleCartRequest(JsonNode request, Context ctx) {
        return new AssistantCartProposal(catalog, trade, mapper, this).handle(request, ctx);
    }

    public List<Map<String, Object>> definitions(String agent) {
        return toolCatalog.definitions(agent);
    }

    static int integer(JsonNode args, String key, int min, int max) {
        var v = args.get(key);
        if (v == null
                || !v.isIntegralNumber()
                || !v.canConvertToInt()
                || v.intValue() < min
                || v.intValue() > max) throw new BusinessException(400, "助手工具参数无效，请重新说明");
        return v.intValue();
    }

    private long productId(Context ctx, JsonNode args) {
        int pos = integer(args, "position", 1, ctx.cards.size());
        return Long.parseLong(ctx.cards.get(pos - 1).get("id").toString());
    }

    private Map<String, Object> card(Product p) {
        var c = new LinkedHashMap<String, Object>();
        c.put("id", p.id().toString());
        c.put("name", p.name());
        c.put(
                "price",
                p.sale() == null || p.sale().price() == null ? null : p.sale().price() / 100.0);
        c.put("category", p.categoryId());
        return c;
    }

    /**
     * Read once per shopping turn; never use chat history as the current cart. No checkout side
     * effects.
     */
    public Map<String, Object> shoppingSnapshot(Context ctx) {
        Map<String, Object> snapshot;
        try {
            var cart = trade.cart(ctx.user, 2L);
            if (cart == null
                    || !cart.path("items").isArray()
                    || cart.path("version").isMissingNode())
                throw new IllegalStateException("Cart snapshot missing");
            var items = new ArrayList<Map<String, Object>>();
            for (var item : cart.path("items")) {
                long id = item.path("productId").asLong();
                int quantity = item.path("quantity").asInt();
                if (id <= 0 || quantity <= 0)
                    throw new IllegalStateException("Invalid cart snapshot");
                String name = "商品名称暂不可用";
                Product detail = null;
                try {
                    var product = catalog.product(id, ctx.user, 2L);
                    if (product != null) { name = product.name(); detail = product; }
                } catch (Exception ignored) {
                    /* Quantity remains authoritative even if the product is unavailable. */
                }
                var row = new LinkedHashMap<String, Object>();
                row.put("productId", Long.toString(id));
                row.put("name", name);
                row.put("quantity", quantity);
                if (ctx.describingCart) {
                    row.put("available", detail != null);
                    if (detail != null) row.put("product", detail);
                }
                items.add(row);
            }
            snapshot =
                    Map.of(
                            "available",
                            true,
                            "version",
                            cart.path("version").asText(),
                            "items",
                            items,
                            "readAt",
                            java.time.Instant.now().toString());
        } catch (Exception ignored) {
            snapshot = Map.of("available", false, "message", "本轮无法读取当前清单，不能推断为空，也不能沿用历史清单状态。");
        }
        ctx.shoppingCartSnapshot = snapshot;
        return snapshot;
    }

    public void capturePurchaseScope(Context ctx, long budget, int quantity, boolean wholeCart) {
        ctx.purchaseScope = AssistantPurchaseScope.capture(budget, quantity, wholeCart, trade.cart(ctx.user, 2L));
        ctx.budgetCents = budget;
    }

    public List<Map<String, Object>> shoppingProductFacts(Context ctx) {
        var facts = new ArrayList<Map<String, Object>>();
        for (var card : ctx.cards.stream().limit(8).toList()) {
            try {
                var product =
                        catalog.product(Long.parseLong(card.get("id").toString()), ctx.user, 2L);
                if (product == null) throw new IllegalStateException("Product unavailable");
                facts.add(
                        Map.of(
                                "available",
                                true,
                                "product",
                                product,
                                "matchesConnectionRequirement",
                                ctx.shoppingConstraints.accepts(product)));
            } catch (Exception ignored) {
                facts.add(Map.of("available", false, "name", String.valueOf(card.get("name"))));
            }
        }
        return facts;
    }

    static String shoppingCartSummary(Map<String, Object> snapshot) {
        if (!Boolean.TRUE.equals(snapshot.get("available"))) return "本次暂未查到采购清单状态，请在清单页面核对。";
        var items = (List<?>) snapshot.get("items");
        if (items.isEmpty()) return "你的采购清单目前为空。";
        var lines = new ArrayList<String>();
        for (var value : items) {
            var row = (Map<?, ?>) value;
            lines.add(row.get("name") + " × " + row.get("quantity") + " 件");
        }
        return "你的采购清单目前有：\n" + String.join("\n", lines);
    }

    public Object execute(String agent, String name, JsonNode args, Context ctx) {
        if (!ALLOWED.getOrDefault(agent, Set.of()).contains(name))
            throw new BusinessException(400, "该助手不能执行此工具");
        if (args == null || !args.isObject()) throw new BusinessException(400, "助手工具参数无效");
        return switch (name) {
            case "compare_products" -> compareProducts(args, ctx);
            case "ask_clarification" -> {
                String reason = args.path("reason").asText();
                if (ctx.cards.isEmpty()
                        && (reason.equals("QUANTITY") || reason.equals("OPERATION")))
                    reason = "PRODUCT";
                ctx.clarification =
                        switch (reason) {
                            case "PRODUCT" ->
                                    "PROCUREMENT".equals(agent)
                                            ? "你想调整哪一款商品？请说明最近商品卡中的选项编号或商品名称。"
                                            : "你想了解哪一款商品？请告诉我商品名称、型号，或最近商品卡中的选项编号。";
                            case "QUANTITY" -> "这款商品你一共需要多少件？";
                            case "OPERATION" -> "你是想把清单里的数量改成这个件数，还是在现有数量上继续增加？";
                            case "UNSUPPORTED" -> "这项操作需要你在订单页面核对并操作，我不能在聊天中代为支付或取消订单。";
                            default -> throw new BusinessException(400, "追问类型无效");
                        };
                yield Map.of("question", ctx.clarification);
            }
            case "prepare_requirement" -> {
                ctx.requirementDraft = AssistantRequirementDraft.parse(args);
                yield Map.of(
                        "prepared",
                        true,
                        "submitted",
                        false,
                        "message",
                        "草稿已整理，客户需点击草稿入口核对并保存，尚未发给商家。");
            }
            case "search_products" -> {
                int category = integer(args, "category", 0, 6);
                String q = args.path("keyword").asText("").strip();
                if (q.length() > 80) throw new BusinessException(400, "搜索词过长");
                var pool = catalog.list(category == 0 ? null : category, 1, 100, ctx.user, 2L);
                var matches =
                        pool.items().stream()
                                .filter(p -> "PUBLISHED".equals(p.status()))
                                .filter(
                                        p ->
                                                !"SHOPPING".equals(agent)
                                                        || ctx.shoppingConstraints.accepts(p))
                                .filter(
                                        p ->
                                                q.isBlank()
                                                        || p.name()
                                                                .toLowerCase(Locale.ROOT)
                                                                .contains(
                                                                        q.toLowerCase(Locale.ROOT))
                                                        || String.valueOf(p.specJson())
                                                                .toLowerCase(Locale.ROOT)
                                                                .contains(
                                                                        q.toLowerCase(Locale.ROOT)))
                                .filter(p -> !"SHOPPING".equals(agent) || ctx.purchaseScope == null
                                        || p.sale() != null && p.sale().price() != null
                                        && p.sale().price() <= ctx.purchaseScope.budget / Math.max(1, ctx.purchaseScope.quantity))
                                .sorted(!"SHOPPING".equals(agent) || ctx.purchaseScope == null
                                        ? (a, b) -> 0
                                        : Comparator.comparingLong((Product p) -> p.sale().price()).thenComparing(Product::id))
                                .limit(8)
                                .toList();
                ctx.catalogSearches.add(
                        Map.of(
                                "category",
                                category,
                                "keyword",
                                q,
                                "returnedProductIds",
                                matches.stream().map(p -> String.valueOf(p.id())).toList()));
                ctx.cards = matches.stream().map(this::card).toList();
                // The returned card array itself defines positions, independently of prose layout.
                ctx.cardReferencesTrusted = true;
                yield Map.of(
                        "cards",
                        ctx.cards,
                        "products",
                        matches,
                        "limited",
                        true,
                        "message",
                        "这是目录候选，不代表所有采购条件都已核实。价格单位：cards为元，sale.price为分。");
            }
            case "read_product" -> {
                var product = catalog.product(productId(ctx, args), ctx.user, 2L);
                if (!"KNOWLEDGE".equals(agent)) yield product;
                // A knowledge lookup includes current evidence, regardless of which read tool the
                // model selected. Do not make the user repeat a question to satisfy tool choreography.
                var evidence = execute("KNOWLEDGE", "read_evidence",
                        mapper.createObjectNode().put("position", args.path("position").asInt())
                                .put("query", "技术参数、连接方式、功能和使用限制"), ctx);
                yield Map.of("product", product, "evidence", evidence);
            }
            case "read_evidence" -> {
                long id = productId(ctx, args);
                catalog.product(id, ctx.user, 2L);
                String query = args.path("query").asText("").strip(), key = id + ":" + query;
                if (ctx.evidenceCache.containsKey(key)) yield ctx.evidenceCache.get(key);
                if (ctx.turnId == null || ctx.evidenceCache.size() >= 3)
                    throw new BusinessException(400, "本次资料检索步骤已达上限");
                var found =
                        evidenceSearch.search(
                                ctx.turnId + ":" + (101 + ctx.evidenceCache.size()),
                                id,
                                ctx.user,
                                query);
                ctx.evidenceCache.put(key, found);
                for (var chunk : found.chunks())
                    if (!ctx.evidence.contains(chunk)) ctx.evidence.add(chunk);
                ctx.retrievals.add(
                        Map.of(
                                "productId",
                                id,
                                "mode",
                                found.mode(),
                                "semantic",
                                found.semantic(),
                                "fallbackReason",
                                found.fallbackReason(),
                                "total",
                                found.total(),
                                "returned",
                                found.chunks().size(),
                                "ranking",
                                found.ranking()));
                yield found;
            }
            case "read_cart" -> {
                var cart = trade.cart(ctx.user, 2L);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (var item : cart.path("items")) {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("productId", item.path("productId").asText());
                    row.put("quantity", item.path("quantity").asInt());
                    try {
                        var p = catalog.product(item.path("productId").asLong(), ctx.user, 2L);
                        row.put("name", p.name());
                        row.put("unitPriceCents", p.sale() == null ? null : p.sale().price());
                    } catch (Exception e) {
                        row.put("name", "商品当前无法读取，请在采购清单中核对");
                    }
                    rows.add(row);
                }
                StringBuilder summary =
                        new StringBuilder(rows.isEmpty() ? "你的采购清单目前是空的。" : "当前采购清单：\n");
                for (var row : rows) {
                    summary.append("- ")
                            .append(row.get("name"))
                            .append(" × ")
                            .append(row.get("quantity"));
                    if (row.get("unitPriceCents") != null)
                        summary.append("，当前单价 ¥")
                                .append(
                                        new java.math.BigDecimal(
                                                        row.get("unitPriceCents").toString())
                                                .movePointLeft(2)
                                                .setScale(2)
                                                .toPlainString());
                    summary.append('\n');
                }
                long total = 0;
                boolean priced = true;
                for (var row : rows) {
                    if (row.get("unitPriceCents") == null) {
                        priced = false;
                        continue;
                    }
                    total =
                            Math.addExact(
                                    total,
                                    Math.multiplyExact(
                                            Long.parseLong(row.get("unitPriceCents").toString()),
                                            ((Number) row.get("quantity")).longValue()));
                }
                if (priced) {
                    summary.append("合计 ¥").append(money(total));
                    Long budget = budget(ctx);
                    if (budget != null && (ctx.purchaseScope == null || ctx.purchaseScope.wholeCart))
                        summary.append("；按最近明确的预算 ¥")
                                .append(money(budget))
                                .append(total > budget ? "，已超出预算。" : "，未超出预算。");
                    if (ctx.purchaseScope != null && !ctx.purchaseScope.wholeCart) {
                        long batchTotal = 0;
                        for (var row : rows)
                            batchTotal = Math.addExact(batchTotal, Math.multiplyExact(
                                    Long.parseLong(row.get("unitPriceCents").toString()),
                                    (long) ctx.purchaseScope.chargeable(Long.parseLong(row.get("productId").toString()),
                                            ((Number) row.get("quantity")).intValue())));
                        summary.append("；本次新增采购合计 ¥").append(money(batchTotal))
                                .append("，预算 ¥").append(money(ctx.purchaseScope.budget))
                                .append(batchTotal > ctx.purchaseScope.budget ? "，已超出预算。" : "，未超出预算。")
                                .append("原有清单商品不计入这笔预算。");
                    }
                } else summary.append("部分商品价格无法读取，暂时无法确认完整合计和预算。");
                ctx.cartSummary = summary + "\n可点击下方链接查看清单，价格与库存以确认页为准。";
                ctx.checkout = true;
                yield Map.of("items", rows, "message", "如需调整，请先确认商品和最终件数。金额单位为人民币分。");
            }
            case "set_cart_quantity", "adjust_cart_quantity" -> {
                if (!ctx.cardReferencesTrusted) {
                    ctx.clarification = "这份历史商品列表的编号需要重新核对。请先让我重新查找商品，再按新展示的选项修改清单；本次没有修改。";
                    yield Map.of("changed", false, "question", ctx.clarification);
                }
                long id = productId(ctx, args);
                boolean relative = "adjust_cart_quantity".equals(name);
                int requested =
                        relative
                                ? integer(args, "delta", -999, 999)
                                : integer(args, "quantity", 0, 999);
                if (relative && requested == 0) throw new BusinessException(400, "增减件数不能为0");
                int position = integer(args, "position", 1, ctx.cards.size());
                boolean confirmed = ctx.confirmedCartAction != null && ctx.confirmedCartAction.matches(id, relative, requested);
                if (!confirmed && (!ctx.cards.equals(ctx.originalCards)
                        || !AssistantIntentRules.authorizes(
                                ctx.requestText,
                                ctx.originalCards,
                                position,
                                relative,
                                requested))) {
                    ctx.clarification =
                            "你希望把“" + ctx.cards.get(position - 1).get("name") + "”设为多少件？请明确最终件数；目前还没有修改清单。";
                    yield Map.of("changed", false, "question", ctx.clarification);
                }
                String fingerprint = name + ":" + requested;
                if (ctx.mutationRequests.containsKey(id)) {
                    if (!fingerprint.equals(ctx.mutationRequests.get(id)))
                        throw new BusinessException(400, "同一轮不能反复调整同一商品，请查看清单后再说明");
                    if (!ctx.mutationResults.containsKey(id))
                        throw new BusinessException(409, "上次调整结果不确定，请先查看清单");
                    yield ctx.mutationResults.get(id);
                }
                var selectedProduct = catalog.product(id, ctx.user, 2L);
                if (requested > 0 && !ctx.shoppingConstraints.accepts(selectedProduct)) {
                    ctx.clarification = "还没有修改清单。这款商品的当前规格不能确认符合你已保留的连接方式要求，请重新筛选，或明确说明要更改选购条件。";
                    yield Map.of("changed", false, "question", ctx.clarification);
                }
                var cart = trade.cart(ctx.user, 2L);
                if (confirmed && cart.path("version").asLong() != ctx.confirmedCartAction.version()) {
                    ctx.clarification = "清单已发生变化，请重新核对后再确认；本次没有修改。";
                    yield Map.of("changed", false, "question", ctx.clarification);
                }
                List<Map<String, Object>> items = new ArrayList<>();
                int previous = 0;
                for (var item : cart.path("items"))
                    if (item.path("productId").asLong() == id)
                        previous = item.path("quantity").asInt();
                int quantity = relative ? previous + requested : requested;
                if (quantity < 0 || quantity > 999)
                    throw new BusinessException(400, "调整后件数须在0至999之间，请核对清单");
                for (var item : cart.path("items"))
                    if (item.path("productId").asLong() != id)
                        items.add(
                                Map.of(
                                        "productId",
                                        item.path("productId").asLong(),
                                        "quantity",
                                        item.path("quantity").asInt()));
                if (quantity > 0) items.add(Map.of("productId", id, "quantity", quantity));
                Long budget = budget(ctx);
                if (budget != null && quantity > previous) {
                    long total = 0;
                    boolean priced = true;
                    for (var item : items) {
                        long itemId = ((Number) item.get("productId")).longValue();
                        int chargedQuantity = ctx.purchaseScope == null
                                ? ((Number) item.get("quantity")).intValue()
                                : ctx.purchaseScope.chargeable(itemId, ((Number) item.get("quantity")).intValue());
                        if (chargedQuantity == 0) continue;
                        var product =
                                catalog.product(
                                        ((Number) item.get("productId")).longValue(), ctx.user, 2L);
                        if (product == null
                                || product.sale() == null
                                || product.sale().price() == null) {
                            priced = false;
                            break;
                        }
                        total =
                                Math.addExact(
                                        total,
                                        Math.multiplyExact(
                                                product.sale().price(),
                                                (long) chargedQuantity));
                    }
                    if (!priced || total > budget) {
                        ctx.clarification =
                                priced
                                        ? "还没有修改清单。" + (ctx.purchaseScope != null && !ctx.purchaseScope.wholeCart ? "本次新增采购合计 ¥" : "调整后清单合计 ¥")
                                                + money(total)
                                                + "，超过最近明确的预算 ¥"
                                                + money(budget)
                                                + "。请减少数量、选择其他商品，或明确说明新的预算。"
                                        : "还没有修改清单。部分商品价格暂不可用，无法核对预算，请先到采购清单确认。";
                        yield Map.of("changed", false, "question", ctx.clarification);
                    }
                }
                // A conflict/timeout is never retried. The customer can inspect the authoritative
                // cart.
                ctx.mutationRequests.put(id, fingerprint);
                var saved =
                        trade.saveCart(
                                Map.of(
                                        "expectedVersion",
                                        cart.path("version").asLong(),
                                        "items",
                                        items),
                                ctx.user,
                                2L);
                ctx.lastCartProductId = quantity > 0 ? id : 0;
                var receipt =
                        Map.of(
                                "previousQuantity",
                                previous,
                                "quantity",
                                quantity,
                                "message",
                                "采购清单已更新，尚未下单");
                ctx.mutationResults.put(id, receipt);
                ctx.cartChanges.add(
                        ctx.cards
                                        .get(integer(args, "position", 1, ctx.cards.size()) - 1)
                                        .get("name")
                                + "："
                                + previous
                                + " → "
                                + quantity
                                + " 件");
                ctx.cartChanged = true;
                yield receipt;
            }
            case "list_orders" -> {
                var orders = trade.orders(ctx.user, 2L);
                List<Map<String, Object>> safe = new ArrayList<>();
                for (var order : orders.path("items")) {
                    var row = new LinkedHashMap<String, Object>();
                    for (String k : List.of("totalAmount", "createdAt", "orderSource"))
                        if (order.has(k))
                            row.put(k, mapper.convertValue(order.get(k), Object.class));
                    row.put(
                            "status",
                            switch (order.path("status").asText()) {
                                case "NO_PAY" -> "待支付";
                                case "PAID" -> "已支付";
                                case "SHIPPED" -> "已发货";
                                case "COMPLETED" -> "已完成";
                                case "CLOSED" -> "已关闭";
                                default -> "状态待确认";
                            });
                    safe.add(row);
                }
                StringBuilder summary =
                        new StringBuilder(safe.isEmpty() ? "你目前没有采购订单。" : "最近的采购订单：\n");
                for (int i = 0; i < safe.size(); i++) {
                    var row = safe.get(i);
                    summary.append(i + 1)
                            .append(". ")
                            .append(row.get("status"))
                            .append(" · ¥")
                            .append(
                                    row.containsKey("totalAmount")
                                            ? new java.math.BigDecimal(
                                                            row.get("totalAmount").toString())
                                                    .movePointLeft(2)
                                                    .setScale(2)
                                                    .toPlainString()
                                            : "待确认")
                            .append('\n');
                }
                ctx.orderSummary = summary + "\n点击下方“查看我的订单”了解详情。";
                ctx.ordersShown = true;
                yield Map.of(
                        "orders",
                        safe,
                        "message",
                        "只列已返回的订单信息；详情请点击回复下方的我的订单入口，不要索取订单编号，本助手没有修改订单工具。");
            }
            case "open_checkout" -> {
                ctx.checkout = true;
                yield Map.of("message", "回复下方已提供采购清单链接，请客户点击后填写收货信息并核对确认。尚未跳转页面或创建订单，不要声称已下单。");
            }
            default -> throw new BusinessException(400, "未知助手工具");
        };
    }

    /** Resolve a bounded comparison together, so searching one model cannot replace another. */
    private Object compareProducts(JsonNode args, Context ctx) {
        JsonNode keywords = args.path("keywords");
        if (!keywords.isArray() || keywords.size() < 2 || keywords.size() > 3)
            throw new BusinessException(400, "一次可比较两到三款商品");
        var names = new LinkedHashSet<String>();
        for (var value : keywords) {
            String keyword = value.asText("").strip().toLowerCase(Locale.ROOT);
            boolean mentioned = ctx.requirementSources.stream()
                    .anyMatch(s -> s.toLowerCase(Locale.ROOT).contains(keyword))
                    || ctx.requestText.toLowerCase(Locale.ROOT).contains(keyword)
                    || ctx.originalCards.stream().anyMatch(c ->
                        String.valueOf(c.get("name")).toLowerCase(Locale.ROOT).contains(keyword));
            if (!value.isTextual() || keyword.length() < 2 || keyword.length() > 80 || !mentioned)
                throw new BusinessException(400, "比较对象需要来自你提到的商品或已展示的选项");
            names.add(keyword);
        }
        if (names.size() != keywords.size())
            throw new BusinessException(400, "请选择不同的商品进行比较");
        var pool = catalog.list(null, 1, 100, ctx.user, 2L);
        var selected = new LinkedHashMap<Long, Product>();
        var unresolved = new ArrayList<String>();
        for (String keyword : names) {
            var matches = pool.items().stream().filter(p -> "PUBLISHED".equals(p.status()))
                    .filter(p -> p.name().toLowerCase(Locale.ROOT).contains(keyword)).toList();
            if (matches.size() != 1) unresolved.add(keyword);
            else selected.put(matches.get(0).id(), matches.get(0));
        }
        if (!unresolved.isEmpty() || selected.size() != names.size()) {
            ctx.clarification = "还不能唯一确定比较对象，请补充完整型号："
                    + String.join("、", unresolved.isEmpty() ? names : unresolved)
                    + "。采购清单没有修改。";
            return Map.of("complete", false, "question", ctx.clarification);
        }
        ctx.cards = selected.values().stream().map(this::card).toList();
        ctx.catalogSearches.add(Map.of("comparisonKeywords", List.copyOf(names),
                "returnedProductIds", selected.keySet().stream().map(String::valueOf).toList()));
        var rows = new ArrayList<Map<String, Object>>();
        int position = 0;
        for (var product : selected.values()) {
            var evidence = execute("KNOWLEDGE", "read_evidence",
                    mapper.createObjectNode().put("position", ++position)
                            .put("query", "技术参数、连接方式、功能和使用限制"), ctx);
            rows.add(Map.of("product", product, "evidence", evidence));
        }
        ctx.cardReferencesTrusted = true;
        ctx.comparisonText = AssistantComparison.render(List.copyOf(selected.values()));
        return Map.of("complete", true, "products", rows, "cards", ctx.cards,
                "message", "这些是比较对象，不代表符合当前购买预算。仅按各自规格与资料对比，缺失信息保留未知；未修改清单。");
    }

    private static String money(long cents) {
        return java.math.BigDecimal.valueOf(cents, 2).setScale(2).toPlainString();
    }

    private static Long budget(Context ctx) {
        return ctx.budgetCents != null
                ? ctx.budgetCents
                : AssistantIntentRules.budget(ctx.requirementSources);
    }
}
