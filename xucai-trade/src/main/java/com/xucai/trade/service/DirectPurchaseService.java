package com.xucai.trade.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.client.catalog.CatalogClient;
import com.xucai.api.dto.mall.CatalogSnapshotDTO;
import com.xucai.api.dto.mall.SaleItemDTO;
import com.xucai.common.exceptions.BusinessException;
import com.xucai.common.utils.ProductMutationLock;
import com.xucai.trade.domain.dto.DirectPurchaseForms.*;
import com.xucai.trade.domain.dto.PurchaseOrderForm.Address;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class DirectPurchaseService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CatalogClient catalog;
    private final ProductMutationLock productLocks;
    private final TransactionTemplate transactions;
    private final PurchaseOrderService orders;

    public record CartView(long version, List<Line> items) {}

    public record Snapshot(String summary, List<PlanDraftRules.Item> items) {}

    private record Intent(
            String id,
            long revision,
            Address address,
            Snapshot snapshot,
            Timestamp expiresAt,
            String orderId) {}

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("采购数据序列化失败", e);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("采购数据读取失败", e);
        }
    }

    private List<Line> lines(String value) {
        try {
            return DirectPurchaseRules.normalize(
                    json.readValue(value, new TypeReference<List<Line>>() {}));
        } catch (Exception e) {
            throw new IllegalStateException("清单记录损坏", e);
        }
    }

    private CartView cart(long buyer, boolean lock) {
        var rows =
                jdbc.query(
                        "SELECT revision,items_json FROM procurement_cart WHERE user_id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        (r, n) ->
                                new CartView(
                                        r.getLong("revision"), lines(r.getString("items_json"))),
                        buyer);
        return rows.isEmpty() ? new CartView(0, List.of()) : rows.get(0);
    }

    public CartView getCart(long buyer) {
        return cart(buyer, false);
    }

    public CartView saveCart(long buyer, Cart form) {
        var items = DirectPurchaseRules.normalize(form.items());
        return transactions.execute(
                tx -> {
                    jdbc.update(
                            "INSERT IGNORE INTO procurement_cart(user_id,revision,items_json) VALUES(?,0,'[]')",
                            buyer);
                    var before = cart(buyer, true);
                    DirectPurchaseRules.version(form.expectedVersion(), before.version());
                    if (items.equals(before.items())) return before;
                    jdbc.update(
                            "UPDATE procurement_cart SET revision=revision+1,items_json=? WHERE user_id=?",
                            encode(items),
                            buyer);
                    return new CartView(before.version() + 1, items);
                });
    }

    private Intent intent(String id, long buyer, boolean lock) {
        var rows =
                jdbc.query(
                        "SELECT * FROM checkout_intent WHERE id=? AND user_id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        (r, n) ->
                                new Intent(
                                        r.getString("id"),
                                        r.getLong("cart_revision"),
                                        decode(r.getString("address_json"), Address.class),
                                        decode(r.getString("snapshot_json"), Snapshot.class),
                                        r.getTimestamp("expires_at"),
                                        r.getString("order_id")),
                        id,
                        buyer);
        if (rows.isEmpty()) throw new BusinessException(404, "订单确认不存在或不属于当前账户");
        return rows.get(0);
    }

    public Map<String, Object> getIntent(String id, long buyer) {
        var i = intent(id, buyer, false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", i.id());
        result.put("cartVersion", i.revision());
        result.put("address", i.address());
        result.put("snapshot", i.snapshot());
        result.put("expiresAt", i.expiresAt().toLocalDateTime().toString());
        result.put("orderId", i.orderId());
        result.put("totalAmount", PlanDraftRules.total(i.snapshot().items()));
        return result;
    }

    private <T> T withProducts(List<Long> ids, int index, Supplier<T> action) {
        return index == ids.size()
                ? action.get()
                : productLocks.execute(ids.get(index), () -> withProducts(ids, index + 1, action));
    }

    private Map<Long, CatalogSnapshotDTO> readProducts(List<Long> ids) {
        Map<Long, CatalogSnapshotDTO> data = new HashMap<>();
        for (long id : ids)
            try {
                data.put(id, catalog.publicSnapshot(id));
            } catch (FeignException e) {
                throw new BusinessException(
                        e.status() == 404 ? 409 : 503,
                        e.status() == 404 ? "商品已下架，请调整清单" : "商品服务暂不可用，请稍后再确认");
            }
        return data;
    }

    private SaleItemDTO sale(long id) {
        var rows =
                jdbc.query(
                        "SELECT * FROM sale_item WHERE product_id=? FOR UPDATE",
                        (r, n) ->
                                new SaleItemDTO(
                                        r.getLong("product_id"),
                                        r.getLong("price"),
                                        r.getInt("stock"),
                                        r.getBoolean("on_sale"),
                                        r.getLong("sale_version")),
                        id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> prepare(long buyer, Prepare form) {
        var before = getCart(buyer);
        DirectPurchaseRules.version(form.expectedVersion(), before.version());
        if (before.items().isEmpty()) throw new BusinessException(409, "请先将商品加入采购清单");
        var ids = before.items().stream().map(Line::productId).sorted().toList();
        var address = PurchaseOrderRules.normalize(form.address());
        return withProducts(
                ids,
                0,
                () -> {
                    // 同现有目录/售卖变更共用商品锁。所有远程调用在DB行锁事务前完成。
                    var products = readProducts(ids);
                    return transactions.execute(
                            tx -> {
                                var current = cart(buyer, true);
                                DirectPurchaseRules.version(before.version(), current.version());
                                List<PlanDraftRules.Item> items = new ArrayList<>();
                                for (var line : current.items())
                                    items.add(
                                            DirectPurchaseRules.item(
                                                    line,
                                                    products.get(line.productId()),
                                                    sale(line.productId())));
                                var snapshot = new Snapshot("普通设备采购", List.copyOf(items));
                                PlanDraftRules.total(items);
                                String id = UUID.randomUUID().toString();
                                jdbc.update(
                                        "INSERT INTO checkout_intent(id,user_id,cart_revision,address_json,snapshot_json,expires_at) VALUES(?,?,?,?,?,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 15 MINUTE))",
                                        id,
                                        buyer,
                                        current.version(),
                                        encode(address),
                                        encode(snapshot));
                                return getIntent(id, buyer);
                            });
                });
    }

    public Map<String, Object> confirm(String id, long buyer) {
        var before = intent(id, buyer, false);
        // 已成功的确认不再依赖目录、库存、有效期或当前清单，网络重发查回原单。
        if (before.orderId() != null) return orders.detail(before.orderId(), buyer, false);
        var ids =
                before.snapshot().items().stream()
                        .map(PlanDraftRules.Item::productId)
                        .sorted()
                        .toList();
        return withProducts(
                ids,
                0,
                () -> {
                    var replay = intent(id, buyer, false);
                    if (replay.orderId() != null)
                        return orders.detail(replay.orderId(), buyer, false);
                    var products = readProducts(ids);
                    return transactions.execute(
                            tx -> {
                                // 商品协调锁→清单→确认记录→SKU升序；每次事务只处理一个买家的清单。
                                var current = cart(buyer, true);
                                var saved = intent(id, buyer, true);
                                if (saved.orderId() != null)
                                    return orders.detail(saved.orderId(), buyer, false);
                                DirectPurchaseRules.version(saved.revision(), current.version());
                                Boolean fresh =
                                        jdbc.queryForObject(
                                                "SELECT expires_at>CURRENT_TIMESTAMP(3) FROM checkout_intent WHERE id=?",
                                                Boolean.class,
                                                id);
                                if (!Boolean.TRUE.equals(fresh))
                                    throw new BusinessException(409, "确认页面已过期，请回清单重新核对");
                                var expected =
                                        saved.snapshot().items().stream()
                                                .map(i -> new Line(i.productId(), i.quantity()))
                                                .toList();
                                if (!DirectPurchaseRules.normalize(expected)
                                        .equals(current.items()))
                                    throw new BusinessException(409, "确认内容与当前清单不一致，请重新核对");
                                for (var item : saved.snapshot().items())
                                    DirectPurchaseRules.confirm(
                                            item,
                                            products.get(item.productId()),
                                            sale(item.productId()));
                                for (var item : saved.snapshot().items()) {
                                    int changed =
                                            jdbc.update(
                                                    "UPDATE sale_item SET stock=stock-? WHERE product_id=? AND stock>=?",
                                                    item.quantity(),
                                                    item.productId(),
                                                    item.quantity());
                                    if (changed != 1)
                                        throw new BusinessException(409, "库存已变化，订单未创建");
                                }
                                String orderId = UUID.randomUUID().toString();
                                jdbc.update(
                                        "INSERT INTO purchase_order(id,user_id,requirement_id,plan_id,total_amount,address_json,snapshot_json,expires_at) VALUES(?,?,NULL,NULL,?,?,?,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 30 MINUTE))",
                                        orderId,
                                        buyer,
                                        PlanDraftRules.total(saved.snapshot().items()),
                                        encode(saved.address()),
                                        encode(saved.snapshot()));
                                for (var item : saved.snapshot().items())
                                    jdbc.update(
                                            "INSERT INTO purchase_order_item(order_id,product_id,quantity,unit_price) VALUES(?,?,?,?)",
                                            orderId,
                                            item.productId(),
                                            item.quantity(),
                                            item.unitPrice());
                                jdbc.update(
                                        "UPDATE checkout_intent SET order_id=? WHERE id=?",
                                        orderId,
                                        id);
                                jdbc.update(
                                        "UPDATE procurement_cart SET revision=revision+1,items_json='[]' WHERE user_id=?",
                                        buyer);
                                return orders.detail(orderId, buyer, false);
                            });
                });
    }
}
