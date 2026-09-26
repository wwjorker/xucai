package com.xucai.trade.service;

import com.xucai.api.dto.mall.CatalogSnapshotDTO;
import com.xucai.api.dto.mall.SaleItemDTO;
import com.xucai.common.exceptions.BusinessException;
import com.xucai.trade.domain.dto.DirectPurchaseForms.Line;
import java.util.*;

public final class DirectPurchaseRules {
    private DirectPurchaseRules() { }
    public static List<Line> normalize(List<Line> lines) {
        if(lines==null || lines.size()>20)throw new BusinessException(400,"清单最多包含20款商品");
        Set<Long> ids=new HashSet<>();
        for(var line:lines) {
            if(line==null || line.productId()==null || line.productId()<1 || line.quantity()==null || line.quantity()<1 || line.quantity()>999)
                throw new BusinessException(400,"商品和数量不合法");
            if(!ids.add(line.productId()))throw new BusinessException(400,"同一商品请合并数量后提交");
        }
        return lines.stream().sorted(Comparator.comparing(Line::productId)).toList();
    }
    public static void version(long expected,long actual) {
        if(expected!=actual)throw new BusinessException(409,"采购清单已变化，请刷新核对，当前内容未被覆盖");
    }
    public static PlanDraftRules.Item item(Line line,CatalogSnapshotDTO product,SaleItemDTO sale) {
        if(product==null || !Objects.equals(product.id(),line.productId()))throw new BusinessException(503,"目录未返回正确商品");
        if(!"PUBLISHED".equals(product.status()))throw new BusinessException(409,"商品已隐藏，请调整清单");
        available(line,sale);
        return new PlanDraftRules.Item(product.id(),product.name(),product.categoryId(),product.specJson(),product.specVersion(),line.quantity(),sale.price(),sale.saleVersion(),List.of());
    }
    private static void available(Line line,SaleItemDTO sale) {
        if(sale==null || !Objects.equals(sale.productId(),line.productId()) || !Boolean.TRUE.equals(sale.onSale()) || sale.price()==null || sale.price()<1)
            throw new BusinessException(409,"商品尚未开售或已停售，请调整清单");
        if(sale.stock()==null || sale.stock()<line.quantity())throw new BusinessException(409,"商品库存不足，请调整数量");
    }
    public static void confirm(PlanDraftRules.Item saved,CatalogSnapshotDTO product,SaleItemDTO sale) {
        item(new Line(saved.productId(),saved.quantity()),product,sale);
        if(!Objects.equals(product.specVersion(),saved.specVersion()))throw new BusinessException(409,"商品规格已变化，请重新核对清单");
        if(!Objects.equals(sale.saleVersion(),saved.saleVersion()) || !Objects.equals(sale.price(),saved.unitPrice()))
            throw new BusinessException(409,"商品价格或售卖状态已变化，请重新确认");
    }
}
