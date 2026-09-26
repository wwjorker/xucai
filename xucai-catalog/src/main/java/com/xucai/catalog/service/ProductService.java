package com.xucai.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.client.trade.SaleClient;
import com.xucai.api.dto.mall.*;
import com.xucai.common.exceptions.BadRequestException;
import com.xucai.common.exceptions.BusinessException;
import com.xucai.catalog.domain.dto.*;
import com.xucai.catalog.domain.vo.ProductVO;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.sql.Statement;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SaleClient saleClient;
    private final com.xucai.common.utils.ProductMutationLock mutationLock;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final DocChunkService chunks;

    private RowMapper<ProductVO> row() {
        return (r,n) -> {
            ProductVO p = new ProductVO();
            p.setId(r.getLong("id")); p.setName(r.getString("name")); p.setBrand(r.getString("brand"));
            p.setSeries(r.getString("series")); p.setCategoryId(r.getInt("category_id"));
            p.setImageUrl(ProductImageRef.url(p.getId(),r.getString("image_url"))); p.setDescription(r.getString("description"));
            try { p.setSpecJson(json.readTree(r.getString("spec_json"))); }
            catch (java.io.IOException e) { throw new IllegalStateException("商品规格JSON损坏",e); }
            p.setSpecVersion(r.getLong("spec_version")); p.setStatus(r.getString("status"));
            return p;
        };
    }

    private ProductVO require(long id, boolean lock) {
        return jdbc.query("SELECT * FROM product WHERE id=?" + (lock ? " FOR UPDATE" : ""),row(),id)
                .stream().findFirst().orElseThrow(() -> new BusinessException(404,"商品不存在"));
    }

    public Map<String,Object> list(boolean admin, int page, int size, Integer categoryId, String keyword) {
        StringBuilder where = new StringBuilder(admin ? " WHERE 1=1" : " WHERE status='PUBLISHED'");
        List<Object> args = new ArrayList<>();
        if (categoryId != null) { where.append(" AND category_id=?"); args.add(categoryId); }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (LOCATE(?,name)>0 OR LOCATE(?,brand)>0 OR LOCATE(?,series)>0)");
            args.add(keyword.trim()); args.add(keyword.trim()); args.add(keyword.trim());
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM product"+where,Long.class,args.toArray());
        args.add(size); args.add((page-1)*size);
        List<ProductVO> items = jdbc.query("SELECT * FROM product"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",row(),args.toArray());
        attachSale(items);
        return Map.of("items",items,"total",total == null ? 0L : total,"page",page,"size",size);
    }

    public ProductVO detail(long id, boolean admin) {
        ProductVO p = require(id,false);
        requirePublicVisibility(p,admin);
        attachSale(List.of(p));
        return p;
    }

    public ProductStateDTO state(long id) {
        ProductVO p = require(id,false);
        return new ProductStateDTO(p.getId(),p.getStatus(),p.getSpecVersion());
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public CatalogSnapshotDTO snapshot(long id) {
        ProductVO p = require(id,false);
        var documents = jdbc.query("SELECT id,version,title,source_url,content FROM product_doc WHERE product_id=? AND status='ACTIVE' ORDER BY id",
                (r,n) -> new CatalogSnapshotDTO.Document(r.getLong("id"),r.getLong("version"),r.getString("title"),r.getString("source_url"),r.getString("content")),id);
        return new CatalogSnapshotDTO(p.getId(),p.getName(),p.getCategoryId(),p.getSpecJson(),p.getSpecVersion(),p.getStatus(),documents);
    }

    // 只对公开读降级。写操作遇到依赖故障必须失败，不能用空数据当作业务许可。
    private SaleBatchDTO readSale(List<Long> ids) {
        try {
            SaleBatchDTO batch = saleClient.batch(ids);
            if (batch != null && batch.available() && batch.items() != null) return batch;
        } catch (FeignException e) {
            log.warn("售卖查询暂不可用，商品数={}，HTTP状态={}",ids.size(),e.status());
        }
        return new SaleBatchDTO(false,List.of());
    }

    private void attachSale(List<ProductVO> products) {
        if (products.isEmpty()) return;
        SaleBatchDTO batch = readSale(products.stream().map(ProductVO::getId).toList());
        Map<Long,SaleItemDTO> byId = new HashMap<>();
        for (SaleItemDTO sale : batch.items()) byId.put(sale.productId(),sale);
        for (ProductVO p : products) {
            SaleItemDTO sale = byId.get(p.getId());
            p.setSale(sale); p.setPriceAvailable(batch.available() && sale != null);
            boolean published = "PUBLISHED".equals(p.getStatus());
            p.setPurchasable(published && sale != null && Boolean.TRUE.equals(sale.onSale()) && sale.stock() > 0);
            p.setPurchaseMessage(!published ? "商品已隐藏或尚未发布" : !batch.available() ? "价格暂不可用"
                    : sale == null || !sale.onSale() ? "暂未开售" : sale.stock() <= 0 ? "暂无库存" : "可购买");
        }
    }

    private void validate(ProductForm form) {
        if (!form.specJson().isObject() || form.specJson().isEmpty() || form.specJson().toString().length()>16000)
            throw new BadRequestException("规格必须是非空JSON对象，且不超过16000字符");
        if (form.imageUrl() != null && !form.imageUrl().isBlank() && !form.imageUrl().startsWith("/api/catalog/products/")) validateUrl(form.imageUrl());
    }

    private void validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null || uri.getUserInfo()!=null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new BadRequestException("来源与图片地址必须是有效的HTTP或HTTPS链接"); }
    }

    @Transactional
    public ProductStateDTO create(ProductForm form) {
        validate(form);
        if(form.imageUrl()!=null && form.imageUrl().startsWith("/")) throw new BadRequestException("请先创建商品，再上传图片");
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            var ps = conn.prepareStatement("INSERT INTO product(name,brand,series,category_id,image_url,description,spec_json) VALUES(?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            ps.setString(1,form.name().trim()); ps.setString(2,form.brand().trim()); ps.setString(3,form.series().trim());
            ps.setInt(4,form.categoryId()); ps.setString(5,form.imageUrl()); ps.setString(6,form.description().trim());
            ps.setString(7,form.specJson().toString()); return ps;
        },key);
        return state(Objects.requireNonNull(key.getKey()).longValue());
    }

    public ProductStateDTO update(long id, ProductForm form) {
        validate(form);
        return mutationLock.execute(id, () -> {
        requireStopped(id);
        return transactions.execute(tx -> {
        ProductVO current = require(id,true);
        if (!current.getSpecVersion().equals(form.expectedSpecVersion()))
            throw new BusinessException(409,"商品规格已变化，请刷新后重试");
        String storedImage = jdbc.queryForObject("SELECT image_url FROM product WHERE id=?",String.class,id);
        String image = ProductImageRef.keepUploaded(id,form.imageUrl(),storedImage);
        if(image!=null && image.startsWith("/")) throw new BadRequestException("商品图片关联已变化，请刷新后重试");
        jdbc.update("UPDATE product SET name=?,brand=?,series=?,category_id=?,image_url=?,description=?,spec_json=?,spec_version=spec_version+1 WHERE id=?",
                form.name().trim(),form.brand().trim(),form.series().trim(),form.categoryId(),image,
                form.description().trim(),form.specJson().toString(),id);
        return state(id);
        });
        });
    }

    // 与开售共用产品锁。先停售再更正，售卖版本随之变化，旧待确认方案必须重新核实。
    public void checkImageUpload(long id, long expectedVersion) {
        if (!require(id,false).getSpecVersion().equals(expectedVersion)) throw new BusinessException(409,"商品已变化，请刷新后再上传");
        requireStopped(id);
    }

    public ProductStateDTO attachImage(long id, long expectedVersion, String key) {
        if (!ProductImageRef.validKey(key)) throw new BadRequestException("无效图片关联");
        return mutationLock.execute(id, () -> {
            requireStopped(id);
            return transactions.execute(tx -> {
                if (!require(id,true).getSpecVersion().equals(expectedVersion)) throw new BusinessException(409,"商品已变化，图片未关联，请刷新核对");
                jdbc.update("UPDATE product SET image_url=?,spec_version=spec_version+1 WHERE id=?","oss:"+key,id);
                return state(id);
            });
        });
    }

    public String imageKey(long id, String file, boolean admin) {
        ProductVO product = require(id,false);
        requirePublicVisibility(product,admin);
        String key = "products/"+file;
        if (!ProductImageRef.validKey(key) || !Objects.equals(product.getImageUrl(),ProductImageRef.url(id,"oss:"+key)))
            throw new BusinessException(404,"商品图片不存在或已更换");
        return key;
    }

    private void requireStopped(long id) {
        var sales=readSale(List.of(id));
        if(!sales.available())throw new BusinessException(503,"交易暂不可用，无法确认停售，更正未保存");
        if(sales.items().stream().anyMatch(s->Boolean.TRUE.equals(s.onSale())))
            throw new BusinessException(409,"修改规格、更换或退役资料前请先停售；受影响方案需重新编制核实");
    }

    public ProductStateDTO changeStatus(long id, ProductStatusForm form) {
        return mutationLock.execute(id, () -> {
            if ("HIDDEN".equals(form.status())) {
                SaleBatchDTO sales = readSale(List.of(id));
                if (!sales.available()) throw new BusinessException(503,"交易暂不可用，无法确认停售，商品未隐藏");
                if (sales.items().stream().anyMatch(item -> Boolean.TRUE.equals(item.onSale())))
                    throw new BusinessException(409,"请先在售卖设置中停售，再隐藏商品");
            }
            // TransactionTemplate返回后事务已提交，随后外层才释放共同锁。
            return transactions.execute(tx -> {
                ProductVO current = require(id,true);
                if (!current.getSpecVersion().equals(form.expectedSpecVersion()))
                    throw new BusinessException(409,"商品规格已变化，请刷新后重试");
                if ("PUBLISHED".equals(form.status())) {
                    Integer sources = jdbc.queryForObject("SELECT COUNT(*) FROM product_doc WHERE product_id=? AND status='ACTIVE'",Integer.class,id);
                    if (sources == null || sources == 0) throw new BusinessException(409,"发布前至少添加一份有效商品资料");
                }
                jdbc.update("UPDATE product SET status=? WHERE id=?",form.status(),id);
                return state(id);
            });
        });
    }

    public List<Map<String,Object>> docs(long id, boolean admin) {
        ProductVO p = require(id,false);
        requirePublicVisibility(p,admin);
        return jdbc.queryForList("SELECT id,product_id AS productId,doc_key AS docKey,version,title,source_url AS sourceUrl,content,status,created_at AS createdAt FROM product_doc WHERE product_id=?" +
                (admin ? "" : " AND status='ACTIVE'") + " ORDER BY doc_key,version DESC",id);
    }

    private void requirePublicVisibility(ProductVO product, boolean admin) {
        if (!admin && !"PUBLISHED".equals(product.getStatus()))
            throw new BusinessException(404,"商品不存在或未公开");
    }

    public Map<String,Object> addDoc(long id, ProductDocForm form) {
        validateUrl(form.sourceUrl());
        return mutationLock.execute(id, () -> {
        if(form.expectedVersion()>0)requireStopped(id);
        return transactions.execute(tx -> {
        require(id,true); // 以父商品行串行化同商品资料版本，避免两次发布产生两个ACTIVE。
        Long version = jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM product_doc WHERE product_id=? AND doc_key=?",Long.class,id,form.docKey());
        if (!Objects.equals(version,form.expectedVersion())) throw new BusinessException(409,"资料已有新版本，请刷新");
        jdbc.update("UPDATE product_doc SET status='SUPERSEDED' WHERE product_id=? AND doc_key=? AND status='ACTIVE'",id,form.docKey());
        GeneratedKeyHolder docKey = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            var ps = conn.prepareStatement("INSERT INTO product_doc(product_id,doc_key,version,title,source_url,content) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1,id); ps.setString(2,form.docKey()); ps.setLong(3,version+1);
            ps.setString(4,form.title().trim()); ps.setString(5,form.sourceUrl()); ps.setString(6,form.content()); return ps;
        },docKey);
        long docId = Objects.requireNonNull(docKey.getKey()).longValue();
        // 片段在同一事务里派生：资料一保存就能查，切分失败只登记FAILED，不影响正文保存。
        chunks.buildInCurrentTransaction(id,docId,version+1,form.content());
        return Map.of("productId",id,"docId",docId,"docKey",form.docKey(),"version",version+1,
                "index",chunks.docState(id,docId).orElseThrow());
        });
        });
    }

    public void retireDoc(long id, long docId) {
        mutationLock.execute(id, () -> {
        requireStopped(id);
        transactions.executeWithoutResult(tx -> {
        ProductVO p = require(id,true);
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT status FROM product_doc WHERE id=? AND product_id=?",docId,id);
        if (rows.isEmpty()) throw new BusinessException(404,"资料不存在");
        if (!"ACTIVE".equals(rows.get(0).get("status"))) throw new BusinessException(409,"只能退役当前有效资料");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM product_doc WHERE product_id=? AND status='ACTIVE'",Integer.class,id);
        if ("PUBLISHED".equals(p.getStatus()) && count != null && count<=1)
            throw new BusinessException(409,"已发布商品必须保留至少一份有效资料，请先添加替代资料或隐藏商品");
        jdbc.update("UPDATE product_doc SET status='RETIRED' WHERE id=?",docId);
        });
        return null;
        });
    }
}
