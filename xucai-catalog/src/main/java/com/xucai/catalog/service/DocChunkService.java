package com.xucai.catalog.service;

import com.xucai.common.exceptions.BadRequestException;
import com.xucai.common.exceptions.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 资料片段：product_doc 正文的派生索引。正文是唯一事实源，片段只记录偏移与派生副本，
 * 任何时候都可以按 docId 整版重建；重建不修改正文，也不影响历史方案引用。
 *
 * 目录查询是明确标注的关键词/结构化筛选。片段就绪不等于向量索引就绪，
 * 引用存在只证明出处，不证明语义上满足采购要求。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocChunkService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /** 向量索引由采购 AI 服务核验，目录端不能据片段状态推断其就绪情况。 */
    public static final Map<String, Object> VECTOR_STATE = Map.of(
            "status", "NOT_CHECKED",
            "reason", "OWNED_BY_AI_SERVICE",
            "message", "本页使用关键词与结构化筛选；向量索引状态由采购检索服务单独核验。片段可用不代表语义检索可用。");
    public static final Map<String, Object> MATCH_MODE = Map.of(
            "mode", "KEYWORD_SUBSTRING",
            "semantic", false,
            "message", "关键词按原文子串匹配，多个词之间是同时包含；按商品与片段顺序返回，不做相关度排序。");

    private static final int MAX_TERMS = 5;

    // ---------- 建立与重建 ----------

    /**
     * 资料保存后在同一事务里建索引。切分与字段容量校验都是纯计算，先算完再写库；
     * 算不出来就只登记 FAILED，正文照常保存，不留半套片段。
     *
     * 注意边界：派生写库与资料保存共用一个事务，所以**只有纯计算阶段的失败**能保住正文；
     * 真正的数据库写入失败（磁盘、死锁等）会把资料保存一起回滚，不要宣称"任何派生失败都不影响正文"。
     */
    void buildInCurrentTransaction(long productId, long docId, long version, String content) {
        List<DocChunker.Chunk> chunks;
        try {
            chunks = DocChunker.split(content);
            checkCapacity(chunks, docId, version);
        } catch (RuntimeException e) {
            log.warn("资料切分或字段校验失败，正文仍已保存。docId={}", docId, e);
            write(productId, docId, version, content, List.of(), "FAILED", message(e));
            return;
        }
        write(productId, docId, version, content, chunks, "READY", null);
    }

    /** 写库前把列宽这类约束当成切分失败处理，免得超长标题把整笔资料保存一起回滚。 */
    private static void checkCapacity(List<DocChunker.Chunk> chunks, long docId, long version) {
        for (DocChunker.Chunk c : chunks) {
            if (c.headingPath().length() > DocChunker.MAX_HEADING)
                throw new IllegalStateException("第" + c.chunkNo() + "段的标题路径超过" + DocChunker.MAX_HEADING + "字");
            if (c.id(docId, version).length() > 80)
                throw new IllegalStateException("第" + c.chunkNo() + "段的片段编号超过80字符");
            if (c.text().isEmpty() || c.end() <= c.start())
                throw new IllegalStateException("第" + c.chunkNo() + "段为空或偏移不合法");
        }
    }

    private void write(long productId, long docId, long version, String content,
                       List<DocChunker.Chunk> chunks, String status, String error) {
        jdbc.update("DELETE FROM product_doc_chunk WHERE doc_id=?", docId);
        jdbc.update("INSERT INTO product_doc_index(doc_id,product_id,version,content_hash,content_length,chunk_count,rule_version,status,error_message)"
                        + " VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE product_id=VALUES(product_id),version=VALUES(version),"
                        + "content_hash=VALUES(content_hash),content_length=VALUES(content_length),chunk_count=VALUES(chunk_count),"
                        + "rule_version=VALUES(rule_version),status=VALUES(status),error_message=VALUES(error_message)",
                docId, productId, version, DocChunker.hash(content), content.length(), chunks.size(),
                DocChunker.RULE_VERSION, status, error);
        for (DocChunker.Chunk c : chunks) {
            jdbc.update("INSERT INTO product_doc_chunk(chunk_id,doc_id,product_id,version,chunk_no,char_start,char_end,heading_path,text,text_length,oversize)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    c.id(docId, version), docId, productId, version, c.chunkNo(), c.start(), c.end(),
                    c.headingPath(), c.text(), c.text().length(), c.oversize());
        }
    }

    private static String message(RuntimeException e) {
        String text = e.getClass().getSimpleName() + ": " + e.getMessage();
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    /** 商家显式重建一份资料的整版片段。只重算派生数据，因此不需要先停售。 */
    public Map<String, Object> rebuild(long productId, long docId) {
        transactions.executeWithoutResult(tx -> {
            // 锁住资料行本身：与同一份资料的并发重建、以及新版本写入串行化。
            var rows = jdbc.queryForList("SELECT product_id,version,content FROM product_doc WHERE id=? AND product_id=? FOR UPDATE", docId, productId);
            if (rows.isEmpty()) throw new BusinessException(404, "资料不存在或不属于该商品");
            var row = rows.get(0);
            buildInCurrentTransaction(productId, docId, ((Number) row.get("version")).longValue(), (String) row.get("content"));
        });
        return docState(productId, docId).orElseThrow(() -> new BusinessException(500, "重建后读不到索引状态"));
    }

    // ---------- 状态 ----------

    /** 一份资料的索引状态。READY 之外的任何状态都不得当成整版可用。 */
    public Optional<Map<String, Object>> docState(long productId, long docId) {
        var docs = jdbc.queryForList("SELECT id,product_id,doc_key,version,title,source_url,status,content FROM product_doc WHERE id=? AND product_id=?", docId, productId);
        return docs.isEmpty() ? Optional.empty() : Optional.of(state(docs.get(0)));
    }

    public List<Map<String, Object>> productStates(long productId) {
        return jdbc.queryForList("SELECT id,product_id,doc_key,version,title,source_url,status,content FROM product_doc WHERE product_id=? ORDER BY doc_key,version DESC", productId)
                .stream().map(this::state).toList();
    }

    private Map<String, Object> state(Map<String, Object> doc) {
        long docId = ((Number) doc.get("id")).longValue();
        String content = (String) doc.get("content");
        var index = jdbc.queryForList("SELECT * FROM product_doc_index WHERE doc_id=?", docId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docId", docId);
        out.put("productId", ((Number) doc.get("product_id")).longValue());
        out.put("docKey", doc.get("doc_key"));
        out.put("version", ((Number) doc.get("version")).longValue());
        out.put("title", doc.get("title"));
        out.put("sourceUrl", doc.get("source_url"));
        out.put("docStatus", doc.get("status"));
        out.put("contentLength", content.length());
        out.put("current", "ACTIVE".equals(doc.get("status")));
        out.put("vectorIndex", VECTOR_STATE);
        if (index.isEmpty()) {
            out.put("indexState", "NOT_BUILT");
            out.put("chunkCount", 0);
            out.put("reason", "尚未建立片段，重建后才能查找。");
            return out;
        }
        var row = index.get(0);
        int declared = ((Number) row.get("chunk_count")).intValue();
        Integer actual = jdbc.queryForObject("SELECT COUNT(*) FROM product_doc_chunk WHERE doc_id=?", Integer.class, docId);
        out.put("chunkCount", declared);
        out.put("builtAt", String.valueOf(row.get("built_at")));
        out.put("ruleVersion", row.get("rule_version"));
        if ("FAILED".equals(row.get("status"))) {
            out.put("indexState", "FAILED");
            out.put("reason", "上次切分失败：" + row.get("error_message"));
        } else if (!DocChunker.RULE_VERSION.equals(row.get("rule_version"))) {
            out.put("indexState", "STALE");
            out.put("reason", "切分规则已升级，需要整版重建。");
        } else if (!DocChunker.hash(content).equals(row.get("content_hash"))) {
            out.put("indexState", "STALE");
            out.put("reason", "片段与当前正文不一致，需要整版重建。");
        } else if (actual == null || actual != declared) {
            out.put("indexState", "STALE");
            out.put("reason", "片段数量与登记不符（登记" + declared + "，实际" + actual + "），需要整版重建。");
        } else {
            out.put("indexState", "READY");
        }
        return out;
    }

    // ---------- 查询与定位 ----------

    public record Query(String keyword, Long productId, Integer categoryId, String docKey,
                        boolean includeUnpublished, int page, int size) { }

    private void requirePublished(long productId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM product WHERE id=? AND status='PUBLISHED')",Boolean.class,productId)))
            throw new BusinessException(404,"商品未发布或已隐藏");
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> publicSearch(long productId,String keyword,int page,int size) {
        requirePublished(productId);
        var result=search(new Query(keyword,productId,null,null,false,page,size));
        var states=jdbc.queryForList("SELECT id,product_id,doc_key,version,title,source_url,status,content FROM product_doc WHERE product_id=? AND status='ACTIVE'",productId)
                .stream().map(this::state).toList();
        result.put("documentStates",states);
        result.put("evidenceReady",states.stream().allMatch(s->"READY".equals(s.get("indexState"))) && ((List<?>)result.get("dropped")).isEmpty());
        return result;
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> publicSnapshot(long productId) {
        requirePublished(productId);
        var product=jdbc.queryForMap("SELECT id,name,category_id,spec_version FROM product WHERE id=?",productId);
        var docs=jdbc.queryForList("SELECT id,product_id,doc_key,version,title,source_url,status,content FROM product_doc WHERE product_id=? AND status='ACTIVE' ORDER BY id",productId);
        if(docs.isEmpty() || docs.size()>40)throw new BusinessException(409,"当前资料为空或超过整版索引限额");
        List<Map<String,Object>> manifests=new ArrayList<>();int expected=0;
        for(var doc:docs) {
            var state=state(doc);
            if(!"READY".equals(state.get("indexState")))throw new BusinessException(409,"资料片段尚未就绪，请先整版重建");
            int count=((Number)state.get("chunkCount")).intValue();expected+=count;
            manifests.add(Map.of("docId",doc.get("id"),"version",doc.get("version"),
                    "contentHash",DocChunker.hash((String)doc.get("content")),"ruleVersion",state.get("ruleVersion"),"chunkCount",count));
        }
        if(expected<1 || expected>200)throw new BusinessException(409,"整版片段数必须为1至200，不能只索引截取部分");
        var all=search(new Query(null,productId,null,null,false,1,201));
        if(((Number)all.get("total")).intValue()!=expected || ((List<?>)all.get("items")).size()!=expected || !((List<?>)all.get("dropped")).isEmpty())
            throw new BusinessException(409,"片段完整性复核失败，不能建立整版索引");
        return Map.of("productId",productId,"productName",product.get("name"),"categoryId",product.get("category_id"),
                "specVersion",product.get("spec_version"),"documents",manifests,"chunks",all.get("items"));
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> publicLocate(long productId,String chunkId) {
        requirePublished(productId);
        var result=locate(chunkId);
        if (((Number)result.get("productId")).longValue()!=productId || !Boolean.TRUE.equals(result.get("current"))
                || !"PUBLISHED".equals(result.get("productStatus")))
            throw new BusinessException(404,"当前商品资料片段不可用");
        return result;
    }

    public Map<String, Object> search(Query q) {
        List<String> terms = terms(q.keyword());
        StringBuilder where = new StringBuilder(
                " FROM product_doc_chunk c"
                        + " JOIN product_doc d ON d.id=c.doc_id"
                        + " JOIN product p ON p.id=c.product_id"
                        + " JOIN product_doc_index x ON x.doc_id=c.doc_id"
                        // 只有当前有效资料、READY 且规则版本一致的片段，才可能作为当前资料返回。
                        + " WHERE d.status='ACTIVE' AND x.status='READY' AND x.rule_version=?"
                        + " AND d.version=c.version AND d.product_id=c.product_id"
                        // 半完成或被改过的索引整份退出：正文哈希必须仍然对得上，片段行数必须等于登记段数。
                        // SHA2(utf8mb4文本,256) 与 Java 端对 UTF-8 字节求的 SHA-256 是同一个值；
                        // 万一两边算不出同一结果，返回的是空结果而不是错误结果，随后 verify 还会再核一次。
                        + " AND x.content_hash=SHA2(d.content,256)"
                        + " AND x.chunk_count=(SELECT COUNT(*) FROM product_doc_chunk z WHERE z.doc_id=x.doc_id)");
        List<Object> args = new ArrayList<>();
        args.add(DocChunker.RULE_VERSION);
        if (!q.includeUnpublished()) where.append(" AND p.status='PUBLISHED'");
        if (q.productId() != null) { where.append(" AND c.product_id=?"); args.add(q.productId()); }
        if (q.categoryId() != null) { where.append(" AND p.category_id=?"); args.add(q.categoryId()); }
        if (q.docKey() != null && !q.docKey().isBlank()) { where.append(" AND d.doc_key=?"); args.add(q.docKey().trim()); }
        for (String term : terms) {
            where.append(" AND (LOCATE(?,c.text)>0 OR LOCATE(?,c.heading_path)>0)");
            args.add(term);
            args.add(term);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
        List<Object> paged = new ArrayList<>(args);
        paged.add(q.size());
        paged.add((long) (q.page() - 1) * q.size());
        var rows = jdbc.queryForList("SELECT c.chunk_id,c.doc_id,c.product_id,c.version,c.chunk_no,c.char_start,c.char_end,"
                + "c.heading_path,c.text,c.text_length,c.oversize,d.doc_key,d.title,d.source_url,d.status AS doc_status,"
                + "x.rule_version,p.name AS product_name,p.category_id,p.status AS product_status"
                + where + " ORDER BY c.product_id,c.doc_id,c.chunk_no LIMIT ? OFFSET ?", paged.toArray());

        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> dropped = new ArrayList<>();
        Map<Long, String[]> sources = new HashMap<>();
        for (var row : rows) {
            long docId = ((Number) row.get("doc_id")).longValue();
            String[] source = sources.computeIfAbsent(docId, key -> {
                var got = jdbc.queryForList("SELECT d.content,x.content_hash FROM product_doc d JOIN product_doc_index x ON x.doc_id=d.id WHERE d.id=?", key);
                return got.isEmpty() ? null : new String[]{(String) got.get(0).get("content"), (String) got.get(0).get("content_hash")};
            });
            String reason = verify(row, source, q.productId());
            if (reason != null) {
                dropped.add(Map.of("chunkId", row.get("chunk_id"), "reason", reason));
                continue;
            }
            items.add(present(row, terms));
        }
        if (!dropped.isEmpty()) log.warn("片段查询丢弃{}条未通过复核的片段：{}", dropped.size(), dropped);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total == null ? 0L : total);
        out.put("page", q.page());
        out.put("size", q.size());
        out.put("terms", terms);
        out.put("match", MATCH_MODE);
        out.put("vectorIndex", VECTOR_STATE);
        out.put("dropped", dropped);
        out.put("note", "结果只来自当前有效资料版本；引用存在只证明出处，不证明该商品满足采购要求。");
        return out;
    }

    /** 返回前再核一次归属与原文定位；核不过就不作为当前资料返回。查询与定位共用这一套判定。 */
    private String verify(Map<String, Object> row, String[] source, Long requestedProduct) {
        if (source == null || source[0] == null) return "CONTENT_MISSING";
        String content = source[0];
        if (source[1] != null && !DocChunker.hash(content).equals(source[1])) return "CONTENT_CHANGED";
        long productId = ((Number) row.get("product_id")).longValue();
        if (requestedProduct != null && requestedProduct != productId) return "OWNERSHIP_MISMATCH";
        long docId = ((Number) row.get("doc_id")).longValue();
        long version = ((Number) row.get("version")).longValue();
        int no = ((Number) row.get("chunk_no")).intValue();
        if (!DocChunker.chunkId(docId, version, no).equals(row.get("chunk_id"))) return "ID_MISMATCH";
        int start = ((Number) row.get("char_start")).intValue();
        int end = ((Number) row.get("char_end")).intValue();
        if (start < 0 || end > content.length() || end <= start) return "RANGE_OUT_OF_TEXT";
        if (!content.substring(start, end).equals(row.get("text"))) return "TEXT_MISMATCH";
        return null;
    }

    private Map<String, Object> present(Map<String, Object> row, List<String> terms) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chunkId", row.get("chunk_id"));
        out.put("productId", ((Number) row.get("product_id")).longValue());
        out.put("productName", row.get("product_name"));
        out.put("categoryId", ((Number) row.get("category_id")).intValue());
        out.put("productStatus", row.get("product_status"));
        out.put("docId", ((Number) row.get("doc_id")).longValue());
        out.put("docKey", row.get("doc_key"));
        out.put("docTitle", row.get("title"));
        out.put("sourceUrl", row.get("source_url"));
        out.put("docStatus", row.get("doc_status"));
        out.put("version", ((Number) row.get("version")).longValue());
        out.put("chunkNo", ((Number) row.get("chunk_no")).intValue());
        out.put("charStart", ((Number) row.get("char_start")).intValue());
        out.put("charEnd", ((Number) row.get("char_end")).intValue());
        out.put("headingPath", row.get("heading_path"));
        // 引用片段时要连规则版本一起冻结：规则一改，同一个编号可能落到别的文字上。
        out.put("ruleVersion", row.get("rule_version"));
        out.put("text", row.get("text"));
        out.put("textLength", ((Number) row.get("text_length")).intValue());
        out.put("oversize", truthy(row.get("oversize")));
        out.put("matches", matches((String) row.get("text"), terms));
        return out;
    }

    /** BOOLEAN 在不同驱动配置下可能读成 Boolean 或数字，两种都要认。 */
    private static boolean truthy(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0;
    }

    /** 命中位置只在片段正文内计算，供页面高亮；不参与排序。 */
    static List<Map<String, Object>> matches(String text, List<String> terms) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String term : terms) {
            int from = 0, found;
            while ((found = text.indexOf(term, from)) >= 0) {
                out.add(Map.of("term", term, "start", found, "length", term.length()));
                from = found + term.length();
                if (out.size() >= 200) return out;
            }
        }
        out.sort(Comparator.comparingInt(m -> (Integer) m.get("start")));
        return out;
    }

    static List<String> terms(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        var terms = new ArrayList<String>();
        for (String part : keyword.trim().split("\\s+")) {
            if (part.isEmpty() || terms.contains(part)) continue;
            terms.add(part);
            if (terms.size() >= MAX_TERMS) break;
        }
        return List.copyOf(terms);
    }

    /** 按稳定ID定位到原文：返回整份资料正文与官方来源，页面据此显示上下文。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> locate(String chunkId) {
        long[] parsed = DocChunker.parseId(chunkId).orElseThrow(() -> new BadRequestException("片段编号格式不正确"));
        var rows = jdbc.queryForList("SELECT c.*,d.doc_key,d.title,d.source_url,d.status AS doc_status,d.content,d.version AS doc_version,"
                + "d.product_id AS doc_product,p.name AS product_name,p.category_id,p.status AS product_status"
                + ",x.rule_version FROM product_doc_chunk c JOIN product_doc d ON d.id=c.doc_id JOIN product p ON p.id=c.product_id"
                + " JOIN product_doc_index x ON x.doc_id=c.doc_id WHERE c.chunk_id=?", chunkId);
        if (rows.isEmpty()) throw new BusinessException(404, "片段不存在；该资料可能尚未建立片段或已重建");
        var row = rows.get(0);
        // 归属复核：编号里的资料与版本必须与资料行、商品行一致，不同商品之间不能串引用。
        if (((Number) row.get("doc_id")).longValue() != parsed[0]
                || ((Number) row.get("doc_version")).longValue() != parsed[1]
                || ((Number) row.get("version")).longValue() != parsed[1]
                || ((Number) row.get("chunk_no")).intValue() != (int) parsed[2]
                || ((Number) row.get("doc_product")).longValue() != ((Number) row.get("product_id")).longValue())
            throw new BusinessException(409, "片段归属与编号不一致，请重建该资料的片段");
        String content = (String) row.get("content");
        var indexState = state(jdbc.queryForList("SELECT id,product_id,doc_key,version,title,source_url,status,content FROM product_doc WHERE id=?", parsed[0]).get(0));
        // 与查询用同一套逐条复核：整份资料的哈希/行数正常、但这一条片段被改坏，同样不算定位成功。
        String integrity = verify(row, new String[]{content, null}, null);
        boolean located = integrity == null;
        boolean active = "ACTIVE".equals(row.get("doc_status"));
        Map<String, Object> out = new LinkedHashMap<>(present(row, List.of()));
        out.put("located", located);
        out.put("integrity", located ? "OK" : integrity);
        // current 必须同时满足：当前有效版本、整份索引 READY、这一条片段本身核得过。
        out.put("current", active && "READY".equals(indexState.get("indexState")) && located);
        out.put("indexState", indexState.get("indexState"));
        out.put("content", content);
        out.put("contentLength", content.length());
        out.put("vectorIndex", VECTOR_STATE);
        out.put("neighbours", jdbc.queryForList("SELECT chunk_id,chunk_no,heading_path FROM product_doc_chunk WHERE doc_id=? AND chunk_no IN (?,?) ORDER BY chunk_no",
                parsed[0], (int) parsed[2] - 1, (int) parsed[2] + 1));
        out.put("note", !located ? "这一条片段与当前正文对不上（" + integrity + "），不能作为引用，请重建该资料的片段。"
                : active ? "片段与原文一致。" : "这是历史资料版本的片段，只用于追溯，不能作为当前资料。");
        return out;
    }
}
