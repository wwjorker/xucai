package com.xucai.trade.service;
import com.xucai.api.dto.mall.*;
import com.xucai.trade.domain.dto.PlanDraftForm;
import com.xucai.common.exceptions.BusinessException;
import java.util.*;
public final class PlanDraftRules {
    private PlanDraftRules() { }
    public record Evidence(Long docId, Long version, String title, String sourceUrl, String content,
                           String quote, String note, String verdict, ChunkSnapshot chunk) { }
    /**
     * 片段引用的不可变快照。方案要长期保存，所以这里冻结的是"当时那段文字在哪、是什么"，
     * 而不是一个将来要回查的键：chunkId只是出处标签，切分规则一改，同一个编号可能落到别的文字上。
     * 真正可复核的是 charStart/charEnd 加 quoteHash —— 拿当时的资料正文就能逐字验回来。
     */
    public record ChunkSnapshot(String chunkId, String ruleVersion, Integer charStart, Integer charEnd,
                                String quoteHash, String verifiedAgainst) { }
    public record Item(Long productId, String name, Integer categoryId,
                       com.fasterxml.jackson.databind.JsonNode specJson, Long specVersion,
                       Integer quantity, Long unitPrice, Long saleVersion, List<Evidence> evidence) { }
    public record Snapshot(RequirementDTO requirement, List<Item> items, List<String> warnings,CandidateRunDTO aiOrigin) {
        public Snapshot(RequirementDTO requirement,List<Item> items,List<String> warnings){this(requirement,items,warnings,null);}
    }
    public static Item item(PlanDraftForm.Item form, CatalogSnapshotDTO p, SaleItemDTO sale) {
        if(p==null || !Objects.equals(p.id(),form.productId())) throw new BusinessException(503,"目录返回的商品不完整");
        if(!"PUBLISHED".equals(p.status())) throw new BusinessException(409,"商品未发布或已隐藏");
        if(!p.specVersion().equals(form.expectedSpecVersion())) throw new BusinessException(409,"商品规格已变化，请重新选择商品");
        if(sale==null || !Boolean.TRUE.equals(sale.onSale())) throw new BusinessException(409,"商品尚未开售或已停售");
        if(!sale.saleVersion().equals(form.expectedSaleVersion())) throw new BusinessException(409,"商品售价已变化，请重新选择商品");
        List<Evidence> evidence=new ArrayList<>();
        Set<Long> seen=new HashSet<>();
        for(var e:form.evidence()) {
            if(!seen.add(e.docId())) throw new BusinessException(400,"同一商品资料只能引用一次");
            var doc=p.documents().stream().filter(d->d.id().equals(e.docId()) && d.version().equals(e.version())).findFirst()
                    .orElseThrow(()->new BusinessException(409,"引用资料不属于该商品、已退役或已有新版本"));
            String quote=e.quote().trim();
            if(!doc.content().contains(quote)) throw new BusinessException(400,"引用文字必须出自所选资料原文");
            // 只核对出处；有原文不等于支持商家的判断。
            evidence.add(new Evidence(doc.id(),doc.version(),doc.title(),doc.sourceUrl(),doc.content(),quote,e.note().trim(),"UNKNOWN",chunk(e,doc,quote)));
        }
        return new Item(p.id(),p.name(),p.categoryId(),p.specJson(),p.specVersion(),form.quantity(),sale.price(),sale.saleVersion(),List.copyOf(evidence));
    }
    /**
     * 在服务层核实实际片段后，用已经拿到的资料正文计算不可变快照；本纯函数不发起远程调用，
     * 也不信任前端传来的任何摘要值——偏移必须逐字还原出同一段摘录，编号必须属于所引用的资料版本。
     */
    static ChunkSnapshot chunk(PlanDraftForm.Evidence e,CatalogSnapshotDTO.Document doc,String quote) {
        var ref=e.chunk();
        if(ref==null)return null;
        String prefix="doc:"+doc.id()+":v"+doc.version()+":c";
        String no=ref.chunkId().startsWith(prefix)?ref.chunkId().substring(prefix.length()):"";
        if(no.isEmpty() || !no.chars().allMatch(Character::isDigit) || no.startsWith("0"))
            throw new BusinessException(400,"片段编号与所选资料版本不一致，请重新从资料片段中选取");
        int start=ref.charStart(),end=ref.charEnd();
        if(start<0 || end<=start || end>doc.content().length())
            throw new BusinessException(409,"片段位置超出资料正文范围，资料可能已更新，请重新选取");
        if(!doc.content().substring(start,end).equals(quote))
            throw new BusinessException(409,"片段位置与摘录文字对不上，资料可能已更新，请重新从资料片段中选取");
        return new ChunkSnapshot(ref.chunkId(),ref.ruleVersion(),start,end,sha256(quote),"DRAFT_TIME_CONTENT");
    }
    /** 拒绝伪造编号/规则和读取期间的版本变化；只证明出处，不提升UNKNOWN判断。 */
    static void verifyChunkSource(PlanDraftForm.Evidence e, CatalogSnapshotDTO product, DocChunkLocationDTO actual) {
        var ref=e.chunk();
        var doc=product.documents().stream().filter(d->d.id().equals(e.docId()) && d.version().equals(e.version())).findFirst()
                .orElseThrow(()->new BusinessException(409,"资料版本已变化，请重新读取商品后选取片段"));
        if(actual==null || !Boolean.TRUE.equals(actual.located()) || !Boolean.TRUE.equals(actual.current())
                || !"READY".equals(actual.indexState()) || !"PUBLISHED".equals(actual.productStatus())
                || !Objects.equals(actual.productId(),product.id()) || !Objects.equals(actual.docId(),e.docId())
                || !Objects.equals(actual.version(),e.version()) || !Objects.equals(actual.chunkId(),ref.chunkId())
                || !Objects.equals(actual.ruleVersion(),ref.ruleVersion()) || !Objects.equals(actual.charStart(),ref.charStart())
                || !Objects.equals(actual.charEnd(),ref.charEnd()) || !Objects.equals(actual.text(),e.quote().trim())
                || !Objects.equals(actual.content(),doc.content()))
            throw new BusinessException(409,"片段与目录当前记录不一致，请重新读取商品后选取片段");
        chunk(e,doc,e.quote().trim());
    }
    static String sha256(String value) {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    public static long total(List<Item> items) {
        return items.stream().mapToLong(i->Math.multiplyExact(i.unitPrice(),i.quantity().longValue())).reduce(0L,Math::addExact);
    }
    public static List<String> warnings(RequirementDTO requirement,List<Item> items,Map<Long,SaleItemDTO> sales) {
        List<String> warnings=new ArrayList<>();
        warnings.add("待核实草稿：用途、必须条件与引用支持关系尚未核实，不能据此确认下单。");
        for(var field:RequirementRules.check(requirement).missing()) warnings.add(field.message());
        if(requirement.structured().budgetCents()!=null && total(items)>requirement.structured().budgetCents()) warnings.add("草稿总额超过需求预算。");
        Map<Integer,Integer> quantities=new TreeMap<>();
        items.forEach(i->quantities.merge(i.categoryId(),i.quantity(),Integer::sum));
        var requested=RequirementRules.itemsForRead(requirement.structured());
        for(var r:requested) if(r.quantity()!=null && !r.quantity().equals(quantities.getOrDefault(r.categoryId(),0))) warnings.add("分类"+r.categoryId()+"的草稿件数与需求不一致。");
        for(var category:quantities.keySet()) if(requested.stream().noneMatch(r->r.categoryId().equals(category))) warnings.add("草稿包含需求未选择的分类"+category+"。");
        for(var i:items) {
            if(i.evidence().isEmpty()) warnings.add(i.name()+"：尚未附上资料依据。");
            if(sales.get(i.productId()).stock()<i.quantity()) warnings.add(i.name()+"：当前库存少于草稿数量，草稿不预占库存。");
        }
        return List.copyOf(warnings);
    }
}
