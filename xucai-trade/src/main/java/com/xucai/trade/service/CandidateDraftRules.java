package com.xucai.trade.service;
import com.xucai.api.dto.mall.*;
import com.xucai.api.dto.mall.CandidateCatalogDTO.Chunk;
import com.xucai.trade.domain.dto.PlanDraftForm;
import com.xucai.common.exceptions.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class CandidateDraftRules {
    private CandidateDraftRules() { }
    public static PlanDraftForm form(CandidateRunDTO run,RequirementDTO req,
            Map<Long,CatalogSnapshotDTO> products,Map<String,DocChunkLocationDTO> locations) {
        if(!Objects.equals(run.requirementId(),req.id()) || !Objects.equals(run.userId(),req.userId()))throw bad("候选不属于此采购需求");
        if(!"OPEN".equals(req.status()) || !Objects.equals(run.requirementRevision(),req.revision()))throw bad("需求已修改或关闭，请使用当前版本的候选");
        var candidate=run.candidate();
        if(candidate==null || !"REFERENCE".equals(candidate.status()) || candidate.items()==null || candidate.items().isEmpty() || candidate.items().size()>6 || run.promptVersion()==null || !run.promptVersion().startsWith("candidate-reference-"))throw bad("此运行不是可采用的商品候选");
        Map<Integer,Integer> expected=new TreeMap<>(),actual=new TreeMap<>();
        for(var i:RequirementRules.itemsForRead(req.structured())){if(i.quantity()==null)throw bad("需求分类件数尚未明确");expected.put(i.categoryId(),i.quantity());}
        Set<Long> ids=new HashSet<>();List<PlanDraftForm.Item> items=new ArrayList<>();long total=0;
        for(var item:candidate.items()) {
            if(!ids.add(item.productId()) || actual.put(item.categoryId(),item.quantity())!=null || item.quantity()<1 || item.quantity()>10000 || item.unitPrice()<1)throw bad("候选商品或分类件数异常");
            var product=products.get(item.productId());
            if(product==null || !Objects.equals(product.categoryId(),item.categoryId()) || !Objects.equals(product.specVersion(),item.specVersion()))throw bad("候选商品分类或规格已变化");
            total=Math.addExact(total,Math.multiplyExact(item.unitPrice(),(long)item.quantity()));
            if(item.evidence()==null || item.evidence().size()>2)throw bad("候选引用异常");
            Map<Long,List<Chunk>> byDoc=new LinkedHashMap<>();Set<String> chunks=new HashSet<>();
            for(var c:item.evidence()) {
                if(!Objects.equals(c.productId(),item.productId()) || !chunks.add(c.chunkId()))throw bad("候选引用不属于商品或重复");
                var evidence=new PlanDraftForm.Evidence(c.docId(),c.version(),c.text(),"AI候选引用，支持关系待商家核实",new PlanDraftForm.Chunk(c.chunkId(),c.ruleVersion(),c.charStart(),c.charEnd()));
                PlanDraftRules.verifyChunkSource(evidence,product,locations.get(c.chunkId()));
                byDoc.computeIfAbsent(c.docId(),id->new ArrayList<>()).add(c);
            }
            List<PlanDraftForm.Evidence> evidence=new ArrayList<>();
            for(var group:byDoc.values()) {
                var first=group.get(0);var doc=product.documents().stream().filter(d->d.id().equals(first.docId()) && d.version().equals(first.version())).findFirst().orElseThrow(()->bad("引用资料已变化"));
                int start=group.stream().mapToInt(Chunk::charStart).min().orElseThrow(),end=group.stream().mapToInt(Chunk::charEnd).max().orElseThrow();
                String quote=doc.content().substring(start,end);
                if(quote.length()>1000)throw bad("同一资料的候选片段跨度超过1000字，请商家人工编制摘录；未丢弃任何片段");
                var single=group.size()==1?new PlanDraftForm.Chunk(first.chunkId(),first.ruleVersion(),start,end):null;
                evidence.add(new PlanDraftForm.Evidence(doc.id(),doc.version(),quote,"AI候选原文，支持关系待核实"+(single==null?"；同资料多片段按连续原文合并，原始片段保存在候选来源中":""),single));
            }
            items.add(new PlanDraftForm.Item(item.productId(),item.specVersion(),item.saleVersion(),item.quantity(),List.copyOf(evidence)));
        }
        if(!expected.equals(actual) || total!=candidate.totalAmount() || req.structured().budgetCents()==null || total>req.structured().budgetCents())throw bad("候选分类、件数或金额与需求不一致");
        String id=UUID.nameUUIDFromBytes(("xucai-candidate:"+run.runId()).getBytes(StandardCharsets.UTF_8)).toString();
        return new PlanDraftForm(id,req.revision(),"AI候选待核实方案",List.copyOf(items));
    }
    private static BusinessException bad(String message){return new BusinessException(409,message);}
}
