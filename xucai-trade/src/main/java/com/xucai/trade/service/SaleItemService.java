package com.xucai.trade.service;

import com.xucai.api.client.catalog.CatalogClient;
import com.xucai.api.dto.mall.*;
import com.xucai.common.exceptions.BusinessException;
import com.xucai.trade.domain.dto.SaleItemForm;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SaleItemService {
    private final JdbcTemplate jdbc;
    private final CatalogClient catalog;
    private final com.xucai.common.utils.ProductMutationLock mutationLock;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private static final RowMapper<SaleItemDTO> ROW = (r,n) -> new SaleItemDTO(r.getLong("product_id"),
            r.getLong("price"), r.getInt("stock"), r.getBoolean("on_sale"), r.getLong("sale_version"));

    public SaleBatchDTO batch(List<Long> ids) {
        List<Long> unique = ids.stream().distinct().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(unique.size(), "?"));
        return new SaleBatchDTO(true, jdbc.query("SELECT * FROM sale_item WHERE product_id IN (" +
                placeholders + ")", ROW, unique.toArray()));
    }

    public SaleItemDTO get(long id) {
        return jdbc.query("SELECT * FROM sale_item WHERE product_id=?", ROW, id).stream().findFirst().orElse(null);
    }

    // 规格属于目录，仅校验存在和发布状态，不跨库写，也不把目录故障当成允许开售。
    public SaleItemDTO save(long id, SaleItemForm form) {
        return mutationLock.execute(id, () -> saveLocked(id,form));
    }

    private SaleItemDTO saveLocked(long id, SaleItemForm form) {
        ProductStateDTO product;
        try { product = catalog.state(id); }
        catch (FeignException e) {
            if (e.status() == 404) throw new BusinessException(404, "商品不存在");
            throw new BusinessException(503, "目录暂不可用，售卖信息未保存");
        }
        if (product == null) throw new BusinessException(503, "目录未返回商品状态");
        if (form.onSale() && !"PUBLISHED".equals(product.status()))
            throw new BusinessException(409, "先发布商品，再开启售卖");
        return transactions.execute(status -> {
        List<SaleItemDTO> rows = jdbc.query("SELECT * FROM sale_item WHERE product_id=? FOR UPDATE", ROW, id);
        // 锁售卖后才建立一致读快照；不再锁订单，避免与取消的订单→售卖顺序形成环。
        Long pending=jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity),0) FROM purchase_order_item i JOIN purchase_order o ON o.id=i.order_id WHERE i.product_id=? AND o.status='NO_PAY'",Long.class,id);
        if(form.stock()+pending>1000000L)
            throw new BusinessException(409,"可用库存与待支付订单预留数量合计不能超过100万，请减少库存录入数量");
        if (rows.isEmpty()) {
            if (form.expectedSaleVersion() != 0 || form.expectedStock() != 0)
                throw new BusinessException(409, "售卖信息尚未建立，请刷新");
            try {
                jdbc.update("INSERT INTO sale_item(product_id,price,stock,on_sale,sale_version) VALUES(?,?,?,?,1)",
                        id,form.price(),form.stock(),form.onSale());
            } catch (DuplicateKeyException e) { throw new BusinessException(409, "售卖信息已创建，请刷新"); }
        } else {
            SaleItemDTO old = rows.get(0);
            if (!old.saleVersion().equals(form.expectedSaleVersion()) || !old.stock().equals(form.expectedStock()))
                throw new BusinessException(409, "价格、状态或库存已变化，请刷新后重试");
            long version = old.saleVersion() + ((!old.price().equals(form.price()) || !old.onSale().equals(form.onSale())) ? 1 : 0);
            jdbc.update("UPDATE sale_item SET price=?,stock=?,on_sale=?,sale_version=? WHERE product_id=?",
                    form.price(),form.stock(),form.onSale(),version,id);
        }
        return get(id);
        });
    }
}
